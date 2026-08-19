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

import sbt.Keys.*
import sbt.internal.LogManager
import sbt.jetbrains.buildServer.sbtlogger.TCLoggerAppender
import sbt.jetbrains.buildServer.sbtlogger.apiAdapter.*
import sbt.plugins.JvmPlugin
import sbt.{Def, *}

/** Native SBT 2 implementation of the TeamCity logger. */
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
    if (System.getProperty(TC_LOGGER_PROPERTY_NAME) == "reloaded") return state

    val extracted = Project.extract(state)
    import extracted.{structure => extractedStructure, *}
    val transformedProjectSettings = extractedStructure.allProjectRefs.flatMap { projectRef =>
      val project = projectScope(projectRef)
      transformSettings(project, projectRef.build, rootProject, SbtTeamCityLogger.projectSettings) ++
        (if tcFound then transformSettings(project, projectRef.build, rootProject, compilerReporterSettings(getScopeId(project.project), projectRef.project)) else Nil) ++
        (if tcFound && !preserveConsole then transformSettings(project, projectRef.build, rootProject, lifecycleSettings(getScopeId(project.project), projectRef.project)) else Nil)
    }
    reapply(session.appendRaw(transformedProjectSettings), state)
  }

  private def transformSettings(thisScope: Scope, uri: URI, rootProject: URI => String, settings: Seq[Setting[?]]): Seq[Setting[?]] =
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
  if tcLoggerVersion == null then System.setProperty(TC_LOGGER_PROPERTY_NAME, "loaded")
  else if tcLoggerVersion == "loaded" then System.setProperty(TC_LOGGER_PROPERTY_NAME, "reloaded")

  private val testResultLoggerFound = try {
    val _: Def.Initialize[TestResultLogger] = Def.setting((Test / test / testResultLogger).value)
    true
  } catch {
    case _: java.lang.NoSuchMethodError => false
  }

  override lazy val projectSettings =
    if tcFound then
      val testSettings =
        if !preserveConsole && testResultLoggerFound then Seq(
          Test / test / testResultLogger := silentTestResultLogger,
          Test / testQuick / testResultLogger := silentTestResultLogger,
          Test / testFull / testResultLogger := silentTestResultLogger
        )
        else Nil

      loggerOnSettings ++ testSettings
    else loggerOffSettings

  private lazy val loggerOnSettings: Seq[Def.Setting[?]] = {
    val ordinaryTaskLogging =
      if preserveConsole then Nil
      else Seq(
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
      Test / testListeners += tcTestListener
    ) ++ ordinaryTaskLogging
  }

  private lazy val loggerOffSettings: Seq[Def.Setting[?]] = Seq(
    commands += tcLoggerStatusCommand
  )

  /** Compile lifecycle is a presentation feature and is deliberately absent in preserve-console observer mode. */
  private def lifecycleSettings(scope: String, projectName: String): Seq[Def.Setting[?]] = Seq(
    update.toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.directDependencyBlockStart(
          dependencyFlowId(scope, "global"),
          Some(projectName)
        )))
        .andFinally(tcLogAppender.directDependencyBlockEnd(dependencyFlowId(scope, "global")))
    },
    (Compile / update).toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.dependencyBlockStart(
          dependencyFlowId(scope, Compile.name),
          Some(projectName),
          inTest = false
        )))
        .andFinally(tcLogAppender.dependencyBlockEnd(dependencyFlowId(scope, Compile.name)))
    },
    (Test / update).toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.dependencyBlockStart(
          dependencyFlowId(scope, Test.name),
          Some(projectName),
          inTest = true
        )))
        .andFinally(tcLogAppender.dependencyBlockEnd(dependencyFlowId(scope, Test.name)))
    },
    Compile / compileIncremental := Def.uncached {
      val _ = (Compile / compile / compileInputs).value
      tcLogAppender.compilationBlockStart(compilerFlowId(scope, Compile.name), Some(projectName))
      val result = (Compile / compileIncremental).result.value
      tcLogAppender.compilationBlockEnd(compilerFlowId(scope, Compile.name), Some(projectName))
      result match {
        case sbt.Result.Value(value) => value
        case sbt.Result.Inc(cause) => throw cause
      }
    },
    Test / compileIncremental := Def.uncached {
      val _ = (Test / compile / compileInputs).value
      tcLogAppender.compilationTestBlockStart(compilerFlowId(scope, Test.name), Some(projectName))
      val result = (Test / compileIncremental).result.value
      tcLogAppender.compilationTestBlockEnd(compilerFlowId(scope, Test.name), Some(projectName))
      result match {
        case sbt.Result.Value(value) => value
        case sbt.Result.Inc(cause) => throw cause
      }
    },
    (Compile / compile).toSettingKey ~= { original =>
      original
        .andFinally(tcLogAppender.compilationBlockEnd(compilerFlowId(scope, Compile.name), Some(projectName)))
    },
    (Test / compile).toSettingKey ~= { original =>
      original
        .andFinally(tcLogAppender.compilationTestBlockEnd(compilerFlowId(scope, Test.name), Some(projectName)))
    }
  )

  private def compilerReporterSettings(scope: String, projectName: String): Seq[Def.Setting[?]] =
    inConfig(Compile)(Seq(reporterSettings(
      tcLogAppender,
      compilerFlowId(scope, Compile.name),
      () => tcLogAppender.compilationBlockStart(compilerFlowId(scope, Compile.name), Some(projectName)),
      reportCompilerOutput = !preserveConsole
    ))) ++
    inConfig(Test)(Seq(reporterSettings(
      tcLogAppender,
      compilerFlowId(scope, Test.name),
      () => tcLogAppender.compilationTestBlockStart(compilerFlowId(scope, Test.name), Some(projectName)),
      reportCompilerOutput = !preserveConsole
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

  private def flowIdFor(key: ScopedKey[?]): String = {
    val scope = key.scope
    val project = getScopeId(scope.project)
    val configuration = scope.config.toOption.map(_.name).getOrElse("global")
    val task = scope.task.toOption.map(_.label).getOrElse("general")
    val phase = phaseForTask(task)
    s"$project:$configuration:$phase"
  }

  private def compilerActivity(key: ScopedKey[?]): () => Unit = {
    val task = key.scope.task.toOption.map(_.label).getOrElse("general")
    if !CompilerTaskNames.contains(task) then () => ()
    else {
      val scope = key.scope
      val project = getScopeId(scope.project)
      val configuration = scope.config.toOption.map(_.name).getOrElse("global")
      val projectName = scope.project.toOption.collect { case project: ProjectRef => project.project }
      if configuration == Test.name then
        () => tcLogAppender.compilationTestBlockStart(compilerFlowId(project, configuration), projectName)
      else
        () => tcLogAppender.compilationBlockStart(compilerFlowId(project, configuration), projectName)
    }
  }

  private def phaseForTask(task: String): String =
    if ResolverTaskNames.contains(task) then "dependency"
    else if CompilerTaskNames.contains(task) then "compiler"
    else s"general:$task"

  private def dependencyFlowId(project: String, configuration: String): String =
    s"$project:$configuration:dependency"

  private def compilerFlowId(project: String, configuration: String): String =
    s"$project:$configuration:compiler"

}
