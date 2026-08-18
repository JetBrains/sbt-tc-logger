/*
 * Copyright 2013-2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.
 */

package jetbrains.buildServer.sbtlogger

import sbt.Configurations.IntegrationTest
import sbt.Keys._
import sbt.internal.LogManager
import sbt.jetbrains.buildServer.sbtlogger.TCLoggerAppender
import sbt.jetbrains.buildServer.sbtlogger.apiAdapter._
import sbt.plugins.JvmPlugin
import sbt.{Def, _}

/** Native SBT 1.4+ implementation of the TeamCity logger. */
object SbtTeamCityLogger extends AutoPlugin with (State => State) {

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  private val PreserveConsoleProperty = "teamcity.sbt.logger.preserveConsole"
  private val ResolverTaskNames = Set(
    "update",
    "updateClassifiers",
    "updateSbtClassifiers",
    "csrConfiguration",
    "projectDescriptors",
    "moduleSettings",
    "csrProject",
    "ivyConfiguration",
    "ivySbt"
  )
  private val CompilerTaskNames = Set("compileIncremental")

  def apply(state: State): State = {
    val sbtLoggerVersion = System.getProperty(TC_LOGGER_PROPERTY_NAME)
    if (sbtLoggerVersion == "reloaded") return state

    val extracted = Project.extract(state)
    import extracted.{structure => extractedStructure, _}
    val transformedProjectSettings = extractedStructure.allProjectRefs.flatMap { projectRef =>
      val project = projectScope(projectRef)
      transformSettings(project, projectRef.build, rootProject, SbtTeamCityLogger.projectSettings) ++
        (if (tcFound) transformSettings(project, projectRef.build, rootProject, lifecycleSettings(getScopeId(project.project), projectRef.project)) else Nil)
    }
    reapply(session.appendRaw(transformedProjectSettings), state)
  }

  private def transformSettings(thisScope: Scope, uri: URI, rootProject: URI => String, settings: Seq[Setting[_]]): Seq[Setting[_]] =
    Project.transform(Scope.resolveScope(thisScope, uri, rootProject), settings)

  private def reapply(session: SessionSettings, state: State): State =
    BuiltinCommands.reapply(session, Project.structure(state), state)

  lazy val tcLogAppender = new TCLogAppender()
  lazy val tcTestListener = new TCReportListener(tcLogAppender)

  val tcVersion: Option[String] = sys.env.get("TEAMCITY_VERSION")
  val tcFound: Boolean = tcVersion.isDefined
  val preserveConsole: Boolean = java.lang.Boolean.getBoolean(PreserveConsoleProperty)

  val TC_LOGGER_PROPERTY_NAME = "TEAMCITY_SBT_LOGGER_VERSION"

  val tcLoggerVersion: String = System.getProperty(TC_LOGGER_PROPERTY_NAME)
  if (tcLoggerVersion == null) System.setProperty(TC_LOGGER_PROPERTY_NAME, "loaded")
  else if (tcLoggerVersion == "loaded") System.setProperty(TC_LOGGER_PROPERTY_NAME, "reloaded")

  private val testResultLoggerFound = try {
    val _: Def.Initialize[sbt.TestResultLogger] = Def.setting((testResultLogger in Test).value)
    true
  } catch {
    case _: java.lang.NoSuchMethodError => false
  }

  override lazy val projectSettings =
    if (tcFound) {
      val testSettings = if (testResultLoggerFound) Seq(
        testResultLogger in (Test, test) := silentTestResultLogger,
        testResultLogger in (Test, testQuick) := (testResultLogger in (Test, test)).value,
        testResultLogger in (IntegrationTest, testQuick) := silentTestResultLogger
      ) else Nil

      loggerOnSettings ++ testSettings
    } else loggerOffSettings

  private lazy val silentTestResultLogger: TestResultLogger = new TestResultLogger {
    def run(log: Logger, results: Tests.Output, taskName: String): Unit = ()
  }

  private lazy val loggerOnSettings: Seq[Def.Setting[_]] = {
    val ordinaryTaskLogging = if (preserveConsole) Nil else Seq(
      logManager := {
        val configuredExtraAppenders = extraAppenders.value
        LogManager.withLoggers(
          // MainAppender applies the effective task log level only to a ConsoleAppender screen. TCLoggerAppender
          // subclasses it so client-mode task events are delivered once without a visible SBT console line.
          screen = (key, _) => new TCLoggerAppender(tcLogAppender, flowIdFor(key), compilerActivity(key)),
          relay = _ => TCLoggerAppender.muted("relay"),
          extra = configuredExtraAppenders
        )
      }
    )

    Seq(
      commands += tcLoggerStatusCommand,
      testListeners += tcTestListener
    ) ++ ordinaryTaskLogging
  }

  private lazy val loggerOffSettings: Seq[Def.Setting[_]] = Seq(
    commands += tcLoggerStatusCommand
  )

  /** compileIncremental and compiler diagnostics run after compileInputs, so they form the compiler activity gate. */
  private def lifecycleSettings(scope: String, projectName: String): Seq[Def.Setting[_]] = Seq(
    update.toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.directDependencyBlockStart(
          dependencyFlowId(scope, "global"),
          Some(projectName)
        )))
        .andFinally(tcLogAppender.directDependencyBlockEnd(dependencyFlowId(scope, "global")))
    },
    (update in Compile).toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.dependencyBlockStart(
          dependencyFlowId(scope, Compile.name),
          Some(projectName),
          inTest = false
        )))
        .andFinally(tcLogAppender.dependencyBlockEnd(dependencyFlowId(scope, Compile.name)))
    },
    (update in Test).toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.dependencyBlockStart(
          dependencyFlowId(scope, Test.name),
          Some(projectName),
          inTest = true
        )))
        .andFinally(tcLogAppender.dependencyBlockEnd(dependencyFlowId(scope, Test.name)))
    },
    compileIncremental in Compile := Def.taskDyn {
      val _ = (compileInputs in (Compile, compile)).value
      tcLogAppender.compilationBlockStart(compilerFlowId(scope, Compile.name), Some(projectName))
      val result = (compileIncremental in Compile).result.value
      Def.task {
        tcLogAppender.compilationBlockEnd(compilerFlowId(scope, Compile.name), Some(projectName))
        result match {
          case Value(value) => value
          case Inc(cause) => throw cause
        }
      }
    }.value,
    compileIncremental in Test := Def.taskDyn {
      val _ = (compileInputs in (Test, compile)).value
      tcLogAppender.compilationTestBlockStart(compilerFlowId(scope, Test.name), Some(projectName))
      val result = (compileIncremental in Test).result.value
      Def.task {
        tcLogAppender.compilationTestBlockEnd(compilerFlowId(scope, Test.name), Some(projectName))
        result match {
          case Value(value) => value
          case Inc(cause) => throw cause
        }
      }
    }.value,
    (compile in Compile).toSettingKey ~= { original =>
      original.andFinally(tcLogAppender.compilationBlockEnd(compilerFlowId(scope, Compile.name), Some(projectName)))
    },
    (compile in Test).toSettingKey ~= { original =>
      original.andFinally(tcLogAppender.compilationTestBlockEnd(compilerFlowId(scope, Test.name), Some(projectName)))
    }
  ) ++
    inConfig(Compile)(Seq(reporterSettings(
      tcLogAppender,
      compilerFlowId(scope, Compile.name),
      () => tcLogAppender.compilationBlockStart(compilerFlowId(scope, Compile.name), Some(projectName))
    ))) ++
    inConfig(Test)(Seq(reporterSettings(
      tcLogAppender,
      compilerFlowId(scope, Test.name),
      () => tcLogAppender.compilationTestBlockStart(compilerFlowId(scope, Test.name), Some(projectName))
    )))

  def tcLoggerStatusCommand: Command = Command.command("sbt-teamcity-logger") { state =>
    println("Plugin sbt-teamcity-logger was loaded.")
    tcVersion match {
      case Some(version) => println(s"TeamCity version='$version'")
      case None => println("TeamCity was not discovered. Logger was switched off.")
    }
    state
  }

  private def getScopeId(scope: ScopeAxis[Reference]): String = scope.hashCode().toString

  private def flowIdFor(key: ScopedKey[_]): String = {
    val scope = key.scope
    val project = getScopeId(scope.project)
    val configuration = scope.config.toOption.map(_.name).getOrElse("global")
    val task = scope.task.toOption.map(_.label).getOrElse("general")
    val phase = phaseForTask(task)
    s"$project:$configuration:$phase"
  }

  private def compilerActivity(key: ScopedKey[_]): () => Unit = {
    val task = key.scope.task.toOption.map(_.label).getOrElse("general")
    if (!CompilerTaskNames.contains(task)) () => ()
    else {
      val scope = key.scope
      val project = getScopeId(scope.project)
      val configuration = scope.config.toOption.map(_.name).getOrElse("global")
      val projectName = scope.project.toOption.collect { case project: ProjectRef => project.project }
      if (configuration == Test.name)
        () => tcLogAppender.compilationTestBlockStart(compilerFlowId(project, configuration), projectName)
      else
        () => tcLogAppender.compilationBlockStart(compilerFlowId(project, configuration), projectName)
    }
  }

  private def phaseForTask(task: String): String =
    if (ResolverTaskNames.contains(task)) "dependency"
    else if (CompilerTaskNames.contains(task)) "compiler"
    else s"general:$task"

  private def dependencyFlowId(project: String, configuration: String): String =
    s"$project:$configuration:dependency"

  private def compilerFlowId(project: String, configuration: String): String =
    s"$project:$configuration:compiler"

}
