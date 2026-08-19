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
import sbt.internal.util.AttributeKey
import sbt.jetbrains.buildServer.sbtlogger.TCLoggerAppender
import sbt.jetbrains.buildServer.sbtlogger.apiAdapter._
import sbt.plugins.JvmPlugin
import sbt.{Def, _}
import sbt.util.Level
import lmcoursier.definitions.CacheLogger

/** Native SBT 1.4+ implementation of the TeamCity logger. */
object SbtTeamCityLogger extends AutoPlugin with (State => State) {

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  private val PreserveConsoleProperty = "teamcity.sbt.logger.preserveConsole"
  private val DetailedDependencyResolutionProperty = "teamcity.sbt.logger.detailedDependencyResolution"
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
  private val CompilerTaskKeys: Set[AttributeKey[_]] = Set(compile.key, compileIncremental.key)

  def apply(state: State): State = {
    val sbtLoggerVersion = System.getProperty(TC_LOGGER_PROPERTY_NAME)
    if (sbtLoggerVersion == "reloaded") return state

    val extracted = Project.extract(state)
    import extracted.{structure => extractedStructure, _}
    val transformedProjectSettings = extractedStructure.allProjectRefs.flatMap { projectRef =>
      val project = projectScope(projectRef)
      transformSettings(project, projectRef.build, rootProject, SbtTeamCityLogger.projectSettings) ++
        (if (tcFound) transformSettings(project, projectRef.build, rootProject, compilerReporterSettings(getScopeId(project.project), projectRef.project)) else Nil) ++
        (if (tcFound && !preserveConsole) {
          val scopeId = getScopeId(project.project)
          transformSettings(project, projectRef.build, rootProject, lifecycleSettings(scopeId, projectRef.project)) ++
            transformSettings(project, projectRef.build, rootProject, detailedDependencySettings(projectRef, projectRef.project, extracted, state))
        } else Nil)
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
  val detailedDependencyResolution: Boolean = java.lang.Boolean.getBoolean(DetailedDependencyResolutionProperty)

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
      val testSettings = if (!preserveConsole && testResultLoggerFound) Seq(
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
          screen = (key, _) => new TCLoggerAppender(tcLogAppender, flowIdFor(key), isCompilerTask(key)),
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

  /** Compile lifecycle is a presentation feature and is deliberately absent in preserve-console observer mode. */
  private def lifecycleSettings(scope: String, projectName: String): Seq[Def.Setting[_]] = Seq(
    compile in Compile := Def.taskDyn {
      // Start before evaluating the original task so that compile-input and compiler log messages share this flow.
      tcLogAppender.compilationBlockStart(compilerFlowId(scope, Compile.name), Some(projectName))
      val result = (compile in Compile).result.value
      Def.task {
        tcLogAppender.compilationBlockEnd(compilerFlowId(scope, Compile.name), Some(projectName))
        result match {
          case Value(value) => value
          case Inc(cause) => throw cause
        }
      }
    }.value,
    compile in Test := Def.taskDyn {
      // See the Compile wrapper above: Test compilation has the same task/logging structure.
      tcLogAppender.compilationTestBlockStart(compilerFlowId(scope, Test.name), Some(projectName))
      val result = (compile in Test).result.value
      Def.task {
        tcLogAppender.compilationTestBlockEnd(compilerFlowId(scope, Test.name), Some(projectName))
        result match {
          case Value(value) => value
          case Inc(cause) => throw cause
        }
      }
    }.value
  )

  /**
   * Coursier only exposes final resource information through `csrLogger`.  We replace its small native
   * `downloaded URL` debug callback only while the opt-in presentation is active; Debug keeps the native logger.
   */
  private def detailedDependencySettings(
    projectRef: ProjectRef,
    projectName: String,
    extracted: Extracted,
    state: State
  ): Seq[Def.Setting[_]] = {
    val coursierEnabled = extracted.getOpt(useCoursier in projectRef)
      .orElse(extracted.getOpt(useCoursier in Global))
      .getOrElse(false)
    if (!detailedDependencyResolution || !coursierEnabled) Nil
    else {
      val global = if (!debugUpdateLogLevel(extracted, state, projectRef, None)) detailedDependencySettingsFor(projectName, "global") else Nil
      val compile = if (!debugUpdateLogLevel(extracted, state, projectRef, Some(Compile))) inConfig(Compile)(detailedDependencySettingsFor(projectName, Compile.name)) else Nil
      val test = if (!debugUpdateLogLevel(extracted, state, projectRef, Some(Test))) inConfig(Test)(detailedDependencySettingsFor(projectName, Test.name)) else Nil
      global ++ compile ++ test
    }
  }

  private def detailedDependencySettingsFor(projectName: String, configuration: String): Seq[Def.Setting[_]] = Seq(
    update.toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.detailedDependencyResolutionStarted(projectName, configuration)))
        .map { report =>
          if (report.stats.cached) tcLogAppender.detailedDependencyReportCacheHit(projectName, configuration)
          report
        }
        .andFinally(tcLogAppender.detailedDependencyResolutionFinished(projectName, configuration))
    },
    csrLogger.toSettingKey ~= { original =>
      sbt.std.TaskExtra.task(Some(new DetailedCoursierLogger(tcLogAppender, projectName, configuration)))
    }
  )

  private def debugUpdateLogLevel(
    extracted: Extracted,
    state: State,
    projectRef: ProjectRef,
    configuration: Option[Configuration]
  ): Boolean = {
    val level = configuration match {
      case Some(config) => extracted.getOpt((logLevel in (projectRef, config, update)))
      case None => extracted.getOpt(logLevel in (projectRef, update))
    }
    level.orElse(state.get(logLevel.key)).contains(Level.Debug)
  }

  private def compilerReporterSettings(scope: String, projectName: String): Seq[Def.Setting[_]] =
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

  private def flowIdFor(key: ScopedKey[_]): String = {
    val scope = key.scope
    val project = getScopeId(scope.project)
    val configuration = scope.config.toOption.map(_.name).getOrElse("global")
    val task = scope.task.toOption.map(_.label).getOrElse("general")
    val phase = if (isCompilerTask(key)) "compiler" else phaseForTask(task)
    s"$project:$configuration:$phase"
  }

  private def isCompilerTask(key: ScopedKey[_]): Boolean =
    key.scope.task.toOption.exists(CompilerTaskKeys.contains)

  private def phaseForTask(task: String): String =
    if (ResolverTaskNames.contains(task)) "dependency"
    else s"general:$task"

  private def compilerFlowId(project: String, configuration: String): String =
    s"$project:$configuration:compiler"

}

/** Version-local boundary for the Coursier API; the shared reporter remains free of sbt implementation classes. */
private final class DetailedCoursierLogger(appender: TCLogAppender, projectName: String, configuration: String) extends CacheLogger {
  override def foundLocally(url: String): Unit =
    appender.detailedDependencyFoundLocally(projectName, configuration, url)

  override def downloadingArtifact(url: String): Unit =
    appender.detailedDependencyDownloading(projectName, configuration, url)

  override def downloadLength(url: String, totalLength: Long, alreadyDownloaded: Long, watching: Boolean): Unit =
    appender.detailedDependencyDownloadLength(projectName, configuration, url, totalLength)

  override def downloadedArtifact(url: String, success: Boolean): Unit =
    appender.detailedDependencyDownloaded(projectName, configuration, url, success)
}
