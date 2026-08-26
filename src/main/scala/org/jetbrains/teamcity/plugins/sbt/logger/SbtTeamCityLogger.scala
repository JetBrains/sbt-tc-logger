// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import sbt.Keys.*
import sbt.internal.LogManager
import sbt.internal.util.AttributeKey
import org.jetbrains.teamcity.plugins.sbt.logger.SbtApiSupport.*
import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.{SbtBuildLogMessageReporter, SbtDependencyResolutionReporter, SbtTaskLogAppender}
import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.compilation.{SbtCompilationConfiguration, SbtCompilationFlow, SbtCompilationReporter}
import org.jetbrains.teamcity.plugins.sbt.logger.reporting.SbtTestReportListener
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.{StandardOutputTeamCityServiceMessageWriter, TeamCityServiceMessageWriter}
import sbt.plugins.JvmPlugin
import sbt.{Def, *}

/** Native implementation of the TeamCity logger for the supported SBT targets. */
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
  private val TestTaskKeys: Set[AttributeKey[?]] = SbtApiAdapter.testTaskKeys

  override def apply(state: State): State = {
    if (SbtTeamCityLoggerSettings.loggerLoadState.contains("reloaded")) return state

    val extracted = Project.extract(state)
    import extracted.{structure as extractedStructure, *}
    val transformedProjectSettings = extractedStructure.allProjectPairs.flatMap { case (resolvedProject, projectRef) =>
      val project = projectScope(projectRef)
      transformSettings(project, projectRef.build, rootProject, SbtTeamCityLogger.projectSettings) ++
        (if (isRunningUnderTeamCity) transformSettings(project, projectRef.build, rootProject, testReportListenerSettings(projectRef, resolvedProject.configurations)) else Nil) ++
        (if (isRunningUnderTeamCity) transformSettings(project, projectRef.build, rootProject, compilerReporterSettings(getScopeId(project.project), projectRef.project)) else Nil) ++
        (if (isRunningUnderTeamCity && !preserveConsole) {
          val scopeId = getScopeId(project.project)
          val resultLoggerSettings = testResultLoggerSettings.settings(extractedStructure, state, projectRef, resolvedProject.configurations, scopeId)
          transformSettings(project, projectRef.build, rootProject, resultLoggerSettings) ++
            transformSettings(project, projectRef.build, rootProject, lifecycleSettings(scopeId, projectRef.project)) ++
            transformSettings(project, projectRef.build, rootProject, detailedDependencyResolutionSettings.settings(projectRef, projectRef.project, extracted, state))
        } else Nil)
    }
    reapply(session.appendRaw(transformedProjectSettings), state)
  }

  private def transformSettings(thisScope: Scope, uri: URI, rootProject: URI => String, settings: Seq[Setting[?]]): Seq[Setting[?]] =
    Project.transform(Scope.resolveScope(thisScope, uri, rootProject), settings)

  private def reapply(session: SessionSettings, state: State): State =
    BuiltinCommands.reapply(session, Project.structure(state), state)

  private lazy val teamCityServiceMessageWriter: TeamCityServiceMessageWriter = new StandardOutputTeamCityServiceMessageWriter
  private lazy val sbtBuildLogMessageReporter = new SbtBuildLogMessageReporter(teamCityServiceMessageWriter)
  private lazy val sbtCompilationReporter = new SbtCompilationReporter(teamCityServiceMessageWriter, sbtBuildLogMessageReporter)
  private lazy val sbtDependencyResolutionReporter = new SbtDependencyResolutionReporter(teamCityServiceMessageWriter)

  private val settings = SbtTeamCityLoggerSettings.extract()
  val teamCityVersion: Option[String] = settings.teamCityVersion
  val isRunningUnderTeamCity: Boolean = teamCityVersion.isDefined
  val preserveConsole: Boolean = settings.preserveConsole
  /** When enabled, replaces configured test-result loggers with TeamCity's silent, failure-preserving logger. */
  val useTeamCityTestResultLogger: Boolean = settings.useTeamCityTestResultLogger
  /** Controls ordinary screen output from standard test tasks; structured TeamCity test events are unaffected. */
  val showTestTaskOutput: Boolean = settings.showTestTaskOutput
  val detailedDependencyResolution: Boolean = settings.detailedDependencyResolution

  private lazy val testResultLoggerSettings = new SbtTestResultLoggerSettings(
    sbtBuildLogMessageReporter,
    useTeamCityTestResultLogger,
    showTestTaskOutput
  )
  private lazy val detailedDependencyResolutionSettings = new SbtDetailedDependencyResolutionSettings(
    sbtDependencyResolutionReporter,
    detailedDependencyResolution
  )

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
            else new SbtTaskLogAppender(sbtBuildLogMessageReporter, flowIdFor(key), compilerReporterFor(key), compilationStartFor(key)),
          relay = _ => SbtTaskLogAppender.muted("relay"),
          extra = configuredExtraAppenders
        )
      }
    )

    Seq(commands += teamCityLoggerStatusCommand) ++ ordinaryTaskLogging
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
      original.andFinally(sbtCompilationReporter.finished(compilationFlow(scope, Some(projectName), SbtCompilationConfiguration.Main)))
    },
    (Test / compileIncremental).toSettingKey ~= { original =>
      original.andFinally(sbtCompilationReporter.finished(compilationFlow(scope, Some(projectName), SbtCompilationConfiguration.Test)))
    },
    (Compile / compile).toSettingKey ~= { original =>
      original.andFinally(sbtCompilationReporter.finished(compilationFlow(scope, Some(projectName), SbtCompilationConfiguration.Main)))
    },
    (Test / compile).toSettingKey ~= { original =>
      original.andFinally(sbtCompilationReporter.finished(compilationFlow(scope, Some(projectName), SbtCompilationConfiguration.Test)))
    }
  )

  private def compilerReporterSettings(scope: String, projectName: String): Seq[Def.Setting[?]] =
    inConfig(Compile)(Seq(SbtCompilerReporterOverrideSettings.settings(
      sbtCompilationReporter,
      teamCityServiceMessageWriter,
      compilationFlow(scope, Some(projectName), SbtCompilationConfiguration.Main),
      reportCompilerOutput = !preserveConsole
    ))) ++
    inConfig(Test)(Seq(SbtCompilerReporterOverrideSettings.settings(
      sbtCompilationReporter,
      teamCityServiceMessageWriter,
      compilationFlow(scope, Some(projectName), SbtCompilationConfiguration.Test),
      reportCompilerOutput = !preserveConsole
    )))

  /**
   * Creates a listener for each concrete scoped test-task evaluation instead of sharing callback state across the
   * build or through configuration/task delegation.
   *
   * SBT serializes repeated executions of one scoped test task and de-duplicates that task inside one execution
   * graph. The target adapter binds every supported task API because `test`, `testOnly`, and related input tasks can
   * each have their own `testListeners` value. Each binding preserves user listeners, removes only an inherited
   * plugin-owned listener, and appends a fresh instance; SBT 2 additionally marks the value uncached.
   */
  private def testReportListenerSettings(
    projectRef: ProjectRef,
    configurations: Seq[Configuration]
  ): Seq[Def.Setting[?]] =
    configurations.flatMap { configuration =>
      SbtApiAdapter.testReportListenerTasks.map { task =>
        val flowNamespace =
          s"${projectRef.build.toASCIIString}#${projectRef.project}:${configuration.name}:${task.taskKey.label}"
        task.install(
          configuration,
          () => SbtTestReportListener.pluginOwned(teamCityServiceMessageWriter, flowNamespace)
        )
      }
    }

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

  private def compilerReporterFor(key: ScopedKey[?]): Option[SbtCompilationReporter] =
    if (isCompilerTask(key)) Some(sbtCompilationReporter) else None

  private def compilationStartFor(key: ScopedKey[?]): Option[() => Unit] = {
    val isCompileIncremental = key.scope.task.toOption.contains(compileIncremental.key)
    val configuration = key.scope.config.toOption.map(_.name)
    if (!isCompileIncremental || !configuration.exists(name => name == Compile.name || name == Test.name)) None
    else {
      val projectName = key.scope.project.toOption.collect { case ProjectRef(_, name) => name }
      if (configuration.contains(Test.name))
        Some(sbtCompilationReporter.guardedStart(compilationFlow(getScopeId(key.scope.project), projectName, SbtCompilationConfiguration.Test)))
      else
        Some(sbtCompilationReporter.guardedStart(compilationFlow(getScopeId(key.scope.project), projectName, SbtCompilationConfiguration.Main)))
    }
  }

  private def isTestTask(key: ScopedKey[?]): Boolean =
    key.scope.task.toOption.exists(TestTaskKeys.contains)

  private def phaseForTask(task: String): String =
    if (ResolverTaskNames.contains(task)) "dependency"
    else s"general:$task"

  private def compilerFlowId(project: String, configuration: String): String =
    s"$project:$configuration:compiler"

  private def compilationFlow(
    scope: String,
    projectName: Option[String],
    configuration: SbtCompilationConfiguration
  ): SbtCompilationFlow =
    SbtCompilationFlow(compilerFlowId(scope, configuration.sbtConfigurationName), projectName, configuration)

}
