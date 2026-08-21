// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import _root_.sbt.Keys.*
import _root_.sbt.internal.LogManager
import _root_.sbt.internal.util.AttributeKey
import _root_.org.jetbrains.teamcity.plugins.sbt.logger.SbtApiAdapter.*
import _root_.org.jetbrains.teamcity.plugins.sbt.logger.buildLog.{SbtBuildEventReporter, SbtCoursierDependencyEventReporter, SbtDependencyResolutionReporter, SbtTaskLogAppender}
import _root_.org.jetbrains.teamcity.plugins.sbt.logger.reporting.{SbtInitializerErrorTestFailureReporter, SbtTestReportListener}
import _root_.org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.{StandardOutputTeamCityServiceMessageWriter, TeamCityServiceMessageWriter}
import _root_.sbt.plugins.JvmPlugin
import _root_.sbt.{Def, *}
import _root_.sbt.util.Level

/** Native SBT 1.4+ implementation of the TeamCity logger. */
object SbtTeamCityLogger extends AutoPlugin with (State => State) {

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

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
  private val CompilerTaskKeys: Set[AttributeKey[?]] = Set(compile.key, compileIncremental.key)
  private val TestTaskKeys: Set[AttributeKey[?]] = Set(test.key, testOnly.key, testQuick.key)

  def apply(state: State): State = {
    if (SbtTeamCityLoggerSettings.loggerLoadState.contains("reloaded")) return state

    val extracted = Project.extract(state)
    import extracted.{structure as extractedStructure, *}
    val transformedProjectSettings = extractedStructure.allProjectPairs.flatMap { case (resolvedProject, projectRef) =>
      val project = projectScope(projectRef)
      transformSettings(project, projectRef.build, rootProject, SbtTeamCityLogger.projectSettings) ++
        (if (isRunningUnderTeamCity) transformSettings(project, projectRef.build, rootProject, compilerReporterSettings(getScopeId(project.project), projectRef.project)) else Nil) ++
        (if (isRunningUnderTeamCity && !preserveConsole) {
          val scopeId = getScopeId(project.project)
          val resultLoggerSettings = testResultLoggerSettings(extractedStructure, state, projectRef, resolvedProject.configurations, scopeId)
          transformSettings(project, projectRef.build, rootProject, resultLoggerSettings) ++
            transformSettings(project, projectRef.build, rootProject, lifecycleSettings(scopeId, projectRef.project)) ++
            transformSettings(project, projectRef.build, rootProject, detailedDependencySettings(projectRef, projectRef.project, extracted, state))
        } else Nil)
    }
    reapply(session.appendRaw(transformedProjectSettings), state)
  }

  private def transformSettings(thisScope: Scope, uri: URI, rootProject: URI => String, settings: Seq[Setting[?]]): Seq[Setting[?]] =
    Project.transform(Scope.resolveScope(thisScope, uri, rootProject), settings)

  private def reapply(session: SessionSettings, state: State): State =
    BuiltinCommands.reapply(session, Project.structure(state), state)

  private lazy val teamCityServiceMessageWriter: TeamCityServiceMessageWriter = new StandardOutputTeamCityServiceMessageWriter
  private lazy val sbtBuildEventReporter = new SbtBuildEventReporter(teamCityServiceMessageWriter)
  private lazy val sbtDependencyResolutionReporter = new SbtDependencyResolutionReporter(teamCityServiceMessageWriter)
  private lazy val sbtTestReportListener = new SbtTestReportListener(teamCityServiceMessageWriter)
  private lazy val sbtInitializerErrorTestFailureReporter = new SbtInitializerErrorTestFailureReporter(teamCityServiceMessageWriter)

  private val settings = SbtTeamCityLoggerSettings.extract()
  val teamCityVersion: Option[String] = settings.teamCityVersion
  val isRunningUnderTeamCity: Boolean = teamCityVersion.isDefined
  val preserveConsole: Boolean = settings.preserveConsole
  /** When enabled, replaces configured test-result loggers with TeamCity's silent, failure-preserving logger. */
  val useTeamCityTestResultLogger: Boolean = settings.useTeamCityTestResultLogger
  /** Controls ordinary screen output from standard test tasks; structured TeamCity test events are unaffected. */
  val showTestTaskOutput: Boolean = settings.showTestTaskOutput
  val detailedDependencyResolution: Boolean = settings.detailedDependencyResolution

  private val loggerLoadStateProperty = SbtTeamCityLoggerSettings.LoggerLoadStateProperty

  private val loggerLoadState: String = SbtTeamCityLoggerSettings.loggerLoadState.orNull
  if (loggerLoadState == null) System.setProperty(loggerLoadStateProperty, "loaded")
  else if (loggerLoadState == "loaded") System.setProperty(loggerLoadStateProperty, "reloaded")

  override lazy val projectSettings = if (isRunningUnderTeamCity) loggerOnSettings else loggerOffSettings

  private lazy val loggerOnSettings: Seq[Def.Setting[?]] = {
    val ordinaryTaskLogging = if (preserveConsole) Nil else Seq(
      logManager := {
        val configuredExtraAppenders = extraAppenders.value
        LogManager.withLoggers(
          // MainAppender applies the effective task log level only to a ConsoleAppender screen. SbtTaskLogAppender
          // subclasses it so client-mode task events are delivered once without a visible SBT console line.
          screen = (key, _) =>
            if (!showTestTaskOutput && isTestTask(key)) SbtTaskLogAppender.muted("test-task")
            else new SbtTaskLogAppender(sbtBuildEventReporter, flowIdFor(key), isCompilerTask(key), compilationStartFor(key), sbtInitializerErrorTestFailureReporter.reportIfInitializerError),
          relay = _ => SbtTaskLogAppender.muted("relay"),
          extra = configuredExtraAppenders
        )
      }
    )

    Seq(
      commands += teamCityLoggerStatusCommand,
      testListeners += sbtTestReportListener
    ) ++ ordinaryTaskLogging
  }

  private lazy val loggerOffSettings: Seq[Def.Setting[?]] = Seq(
    commands += teamCityLoggerStatusCommand
  )

  /**
   * Compile lifecycle is a presentation feature and is deliberately absent in preserve-console observer mode.
   *
   * TODO: Support compilable custom configurations by enumerating configurations that define `compileIncremental`,
   * then installing their reporter, lifecycle finalizers, lazy appender start, flow ID, and configuration-aware title.
   */
  private def lifecycleSettings(scope: String, projectName: String): Seq[Def.Setting[?]] = Seq(
    (Compile / compileIncremental).toSettingKey ~= { original =>
      original.andFinally(sbtBuildEventReporter.compilationFinished(compilerFlowId(scope, Compile.name), Some(projectName)))
    },
    (Test / compileIncremental).toSettingKey ~= { original =>
      original.andFinally(sbtBuildEventReporter.testCompilationFinished(compilerFlowId(scope, Test.name), Some(projectName)))
    },
    (Compile / compile).toSettingKey ~= { original =>
      original.andFinally(sbtBuildEventReporter.compilationFinished(compilerFlowId(scope, Compile.name), Some(projectName)))
    },
    (Test / compile).toSettingKey ~= { original =>
      original.andFinally(sbtBuildEventReporter.testCompilationFinished(compilerFlowId(scope, Test.name), Some(projectName)))
    }
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
  ): Seq[Def.Setting[?]] = {
    val coursierEnabled = extracted.getOpt(projectRef / useCoursier)
      .orElse(extracted.getOpt(Global / useCoursier))
      .getOrElse(false)
    if (!detailedDependencyResolution || !coursierEnabled) Nil
    else {
      val global = if (!debugUpdateLogLevel(extracted, state, projectRef, None)) detailedDependencySettingsFor(projectName, "global") else Nil
      val compile = if (!debugUpdateLogLevel(extracted, state, projectRef, Some(Compile))) inConfig(Compile)(detailedDependencySettingsFor(projectName, Compile.name)) else Nil
      val test = if (!debugUpdateLogLevel(extracted, state, projectRef, Some(Test))) inConfig(Test)(detailedDependencySettingsFor(projectName, Test.name)) else Nil
      global ++ compile ++ test
    }
  }

  private def detailedDependencySettingsFor(projectName: String, configuration: String): Seq[Def.Setting[?]] = Seq(
    update.toSettingKey ~= { original =>
      original
        .dependsOn(_root_.sbt.std.TaskExtra.task(sbtDependencyResolutionReporter.started()))
        .map { report =>
          if (report.stats.cached) sbtDependencyResolutionReporter.reportCacheHit(projectName, configuration)
          report
        }
        .andFinally(sbtDependencyResolutionReporter.finished())
    },
    csrLogger.toSettingKey ~= { original =>
      _root_.sbt.std.TaskExtra.task(Some(new SbtCoursierDependencyEventReporter(sbtDependencyResolutionReporter, projectName, configuration)))
    }
  )

  private def debugUpdateLogLevel(
    extracted: Extracted,
    state: State,
    projectRef: ProjectRef,
    configuration: Option[Configuration]
  ): Boolean = {
    val level = configuration match {
      case Some(config) => extracted.getOpt(projectRef / config / update / logLevel)
      case None => extracted.getOpt(projectRef / update / logLevel)
    }
    level.orElse(state.get(logLevel.key)).contains(Level.Debug)
  }

  private def compilerReporterSettings(scope: String, projectName: String): Seq[Def.Setting[?]] =
    inConfig(Compile)(Seq(reporterSettings(
      sbtBuildEventReporter,
      teamCityServiceMessageWriter,
      compilerFlowId(scope, Compile.name),
      () => sbtBuildEventReporter.compilationStarted(compilerFlowId(scope, Compile.name), Some(projectName)),
      reportCompilerOutput = !preserveConsole
    ))) ++
    inConfig(Test)(Seq(reporterSettings(
      sbtBuildEventReporter,
      teamCityServiceMessageWriter,
      compilerFlowId(scope, Test.name),
      () => sbtBuildEventReporter.testCompilationStarted(compilerFlowId(scope, Test.name), Some(projectName)),
      reportCompilerOutput = !preserveConsole
    )))

  def teamCityLoggerStatusCommand: Command = Command.command("sbt-teamcity-logger") { state =>
    println("TeamCity sbt logger")
    println(s"  Version: $loggerVersion")
    teamCityVersion match {
      case Some(version) =>
        println(s"  TeamCity: $version")
        println("  Status: active")
      case None =>
        println("  TeamCity: not detected")
        println("  Status: inactive")
    }
    settings.headerLines.foreach(println)
    state
  }

  private def loggerVersion: String =
    Option(getClass.getPackage)
      .flatMap(loggerPackage => Option(loggerPackage.getImplementationVersion))
      .getOrElse("unknown")

  private def getScopeId(scope: ScopeAxis[Reference]): String = scope.hashCode().toString

  private def flowIdFor(key: ScopedKey[?]): String = {
    val scope = key.scope
    val project = getScopeId(scope.project)
    val configuration = scope.config.toOption.map(_.name).getOrElse("global")
    val task = scope.task.toOption.map(_.label).getOrElse("general")
    val phase = if (isCompilerTask(key)) "compiler" else phaseForTask(task)
    s"$project:$configuration:$phase"
  }

  private def isCompilerTask(key: ScopedKey[?]): Boolean =
    key.scope.task.toOption.exists(CompilerTaskKeys.contains)

  private def compilationStartFor(key: ScopedKey[?]): Option[() => Unit] = {
    val isCompileIncremental = key.scope.task.toOption.contains(compileIncremental.key)
    val configuration = key.scope.config.toOption.map(_.name)
    if (!isCompileIncremental || !configuration.exists(name => name == Compile.name || name == Test.name)) None
    else {
      val flowId = flowIdFor(key)
      val projectName = key.scope.project.toOption.collect { case ProjectRef(_, name) => name }
      if (configuration.contains(Test.name))
        Some(sbtBuildEventReporter.testCompilationStartedCallback(flowId, projectName))
      else
        Some(sbtBuildEventReporter.compilationStartedCallback(flowId, projectName))
    }
  }

  private def isTestTask(key: ScopedKey[?]): Boolean =
    key.scope.task.toOption.exists(TestTaskKeys.contains)

  private def testResultLoggerSettings(
    structure: _root_.sbt.internal.BuildStructure,
    state: State,
    projectRef: ProjectRef,
    configurations: Seq[Configuration],
    scopeId: String
  ): Seq[Def.Setting[?]] = configurations.flatMap { configuration =>
    settingWhenDefined(structure, projectRef, configuration, test.key,
      configuration / test / testResultLogger ~= controlledTestResultLogger(
        resultFlowId(scopeId, configuration, test.key),
        taskScreenLogLevel(structure, state, projectRef, configuration, test.key)
      )) ++
      settingWhenDefined(structure, projectRef, configuration, testOnly.key,
        configuration / testOnly / testResultLogger ~= controlledTestResultLogger(
          resultFlowId(scopeId, configuration, testOnly.key),
          taskScreenLogLevel(structure, state, projectRef, configuration, testOnly.key)
        )) ++
      settingWhenDefined(structure, projectRef, configuration, testQuick.key,
        configuration / testQuick / testResultLogger ~= controlledTestResultLogger(
          resultFlowId(scopeId, configuration, testQuick.key),
          taskScreenLogLevel(structure, state, projectRef, configuration, testQuick.key)
        ))
  }

  private def settingWhenDefined(
    structure: _root_.sbt.internal.BuildStructure,
    projectRef: ProjectRef,
    configuration: Configuration,
    taskKey: AttributeKey[?],
    setting: => Def.Setting[?]
  ): Seq[Def.Setting[?]] = {
    val scope = Scope(Select(projectRef), Select(configuration), Select(taskKey), Zero)
    if (structure.data.get(scope, testResultLogger.key).isDefined) Seq(setting) else Nil
  }

  private def controlledTestResultLogger(
    flowId: String,
    screenLevel: Level.Value
  )(configured: TestResultLogger): TestResultLogger =
    if (useTeamCityTestResultLogger) silentTestResultLogger
    else if (showTestTaskOutput) configured
    else adaptTestResultLoggerForTeamCity(configured, sbtBuildEventReporter, flowId, screenLevel, sbtInitializerErrorTestFailureReporter.reportIfInitializerError)

  private def taskScreenLogLevel(
    structure: _root_.sbt.internal.BuildStructure,
    state: State,
    projectRef: ProjectRef,
    configuration: Configuration,
    taskKey: AttributeKey[?]
  ): Level.Value = {
    val scope = Scope(Select(projectRef), Select(configuration), Select(taskKey), Zero)
    LogManager.getOr(logLevel.key, structure.data, scope, state, Level.Info)
  }

  private def resultFlowId(project: String, configuration: Configuration, taskKey: AttributeKey[?]): String =
    s"$project:${configuration.name}:general:${taskKey.label}"

  private def phaseForTask(task: String): String =
    if (ResolverTaskNames.contains(task)) "dependency"
    else s"general:$task"

  private def compilerFlowId(project: String, configuration: String): String =
    s"$project:$configuration:compiler"

}
