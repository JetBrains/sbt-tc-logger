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
import sbt.internal.util.AttributeKey
import sbt.jetbrains.buildServer.sbtlogger.TCLoggerAppender
import sbt.jetbrains.buildServer.sbtlogger.apiAdapter.*
import sbt.plugins.JvmPlugin
import sbt.{Def, *}
import sbt.util.Level
import lmcoursier.definitions.CacheLogger

/** Native SBT 2 implementation of the TeamCity logger. */
object SbtTeamCityLogger extends AutoPlugin with (State => State) {

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  private val PreserveConsoleProperty = "teamcity.sbt.logger.preserveConsole"
  private val UseTeamCityTestResultLoggerProperty = "teamcity.sbt.logger.useTeamCityTestResultLogger"
  private val ShowTestTaskOutputProperty = "teamcity.sbt.logger.showTestTaskOutput"
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
  private val CompilerTaskKeys: Set[AttributeKey[?]] = Set(compile.key, compileIncremental.key)
  private val TestTaskKeys: Set[AttributeKey[?]] = Set(test.key, testOnly.key, testSelected.key, testQuick.key, testFull.key)

  def apply(state: State): State = {
    if (System.getProperty(TC_LOGGER_PROPERTY_NAME) == "reloaded") return state

    val extracted = Project.extract(state)
    import extracted.{structure => extractedStructure, *}
    val transformedProjectSettings = extractedStructure.allProjectPairs.flatMap { case (resolvedProject, projectRef) =>
      val project = projectScope(projectRef)
      transformSettings(project, projectRef.build, rootProject, SbtTeamCityLogger.projectSettings) ++
        (if tcFound then transformSettings(project, projectRef.build, rootProject, compilerReporterSettings(getScopeId(project.project), projectRef.project)) else Nil) ++
        (if tcFound && !preserveConsole then
          val scopeId = getScopeId(project.project)
          val resultLoggerSettings =
            if testResultLoggerFound then
              testResultLoggerSettings(extractedStructure, state, projectRef, resolvedProject.configurations, scopeId)
            else Nil
          transformSettings(project, projectRef.build, rootProject, resultLoggerSettings) ++
            transformSettings(project, projectRef.build, rootProject, lifecycleSettings(scopeId, projectRef.project)) ++
            transformSettings(project, projectRef.build, rootProject, detailedDependencySettings(projectRef, projectRef.project, extracted, state))
        else Nil)
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
  /** When enabled, replaces configured test-result loggers with TeamCity's silent, failure-preserving logger. */
  val useTeamCityTestResultLogger: Boolean = booleanProperty(UseTeamCityTestResultLoggerProperty, defaultValue = true)
  /** Controls ordinary screen output from standard test tasks; structured TeamCity test events are unaffected. */
  val showTestTaskOutput: Boolean = booleanProperty(ShowTestTaskOutputProperty, defaultValue = true)
  val detailedDependencyResolution: Boolean = java.lang.Boolean.getBoolean(DetailedDependencyResolutionProperty)

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

  override lazy val projectSettings = if tcFound then loggerOnSettings else loggerOffSettings

  private lazy val loggerOnSettings: Seq[Def.Setting[?]] = {
    val ordinaryTaskLogging =
      if preserveConsole then Nil
      else Seq(
        logManager := {
          val configuredExtraAppenders = extraAppenders.value
          LogManager.withLoggers(
            // MainAppender applies the effective task log level only to a ConsoleAppender screen. TCLoggerAppender
            // subclasses it so client-mode task events are delivered once without a visible SBT console line.
            screen = (key, _) =>
              if !showTestTaskOutput && isTestTask(key) then TCLoggerAppender.muted("test-task")
              else new TCLoggerAppender(tcLogAppender, flowIdFor(key), isCompilerTask(key)),
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
    (Compile / compile).toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.compilationBlockStart(compilerFlowId(scope, Compile.name), Some(projectName))))
        .andFinally(tcLogAppender.compilationBlockEnd(compilerFlowId(scope, Compile.name), Some(projectName)))
    },
    (Test / compile).toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.compilationTestBlockStart(compilerFlowId(scope, Test.name), Some(projectName))))
        .andFinally(tcLogAppender.compilationTestBlockEnd(compilerFlowId(scope, Test.name), Some(projectName)))
    }
  )

  /** Replaces Coursier's native Debug-only `downloaded URL` callback while detailed normal-mode reporting is active. */
  private def detailedDependencySettings(
    projectRef: ProjectRef,
    projectName: String,
    extracted: Extracted,
    state: State
  ): Seq[Def.Setting[?]] =
    if !detailedDependencyResolution then Nil
    else
      val global = if !debugUpdateLogLevel(extracted, state, projectRef, None) then detailedDependencySettingsFor(projectName, "global") else Nil
      val compile = if !debugUpdateLogLevel(extracted, state, projectRef, Some(Compile)) then inConfig(Compile)(detailedDependencySettingsFor(projectName, Compile.name)) else Nil
      val test = if !debugUpdateLogLevel(extracted, state, projectRef, Some(Test)) then inConfig(Test)(detailedDependencySettingsFor(projectName, Test.name)) else Nil
      global ++ compile ++ test

  private def detailedDependencySettingsFor(projectName: String, configuration: String): Seq[Def.Setting[?]] = Seq(
    update.toSettingKey ~= { original =>
      original
        .dependsOn(sbt.std.TaskExtra.task(tcLogAppender.detailedDependencyResolutionStarted(projectName, configuration)))
        .mapN { report =>
          if report.stats.cached then tcLogAppender.detailedDependencyReportCacheHit(projectName, configuration)
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
    val level = configuration match
      case Some(config) => extracted.getOpt(projectRef / config / update / logLevel)
      case None => extracted.getOpt(projectRef / update / logLevel)
    level.orElse(state.get(logLevel.key)).contains(Level.Debug)
  }

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
    println("TeamCity sbt logger")
    println(s"  Version: $loggerVersion")
    tcVersion match {
      case Some(version) =>
        println(s"  TeamCity: $version")
        println("  Status: active")
      case None =>
        println("  TeamCity: not detected")
        println("  Status: inactive")
    }
    println(s"  Preserve SBT console: ${booleanSetting(PreserveConsoleProperty, preserveConsole)}")
    println(s"  Use TeamCity test result logger: ${booleanSetting(UseTeamCityTestResultLoggerProperty, useTeamCityTestResultLogger, defaultValue = true, overridden = preserveConsole)}")
    println(s"  Show test-task output: ${booleanSetting(ShowTestTaskOutputProperty, showTestTaskOutput, defaultValue = true, overridden = preserveConsole)}")
    println(s"  Detailed dependency resolution: ${booleanSetting(DetailedDependencyResolutionProperty, detailedDependencyResolution)}")
    state
  }

  private def loggerVersion: String =
    Option(getClass.getPackage)
      .flatMap(loggerPackage => Option(loggerPackage.getImplementationVersion))
      .getOrElse("unknown")

  private def booleanProperty(property: String, defaultValue: Boolean): Boolean =
    Option(System.getProperty(property)).fold(defaultValue)(java.lang.Boolean.parseBoolean)

  private def booleanSetting(
    property: String,
    value: Boolean,
    defaultValue: Boolean = false,
    overridden: Boolean = false
  ): String =
    val annotations =
      (if System.getProperty(property) == null && value == defaultValue then Seq("default") else Nil) ++
        (if overridden then Seq("overridden by preserveConsole") else Nil)
    s"$value${if annotations.nonEmpty then s" (${annotations.mkString("; ")})" else ""}"

  private def getScopeId(scope: ScopeAxis[Reference]): String = scope.hashCode().toString

  private def flowIdFor(key: ScopedKey[?]): String = {
    val scope = key.scope
    val project = getScopeId(scope.project)
    val configuration = scope.config.toOption.map(_.name).getOrElse("global")
    val task = scope.task.toOption.map(_.label).getOrElse("general")
    val phase = if isCompilerTask(key) then "compiler" else phaseForTask(task)
    s"$project:$configuration:$phase"
  }

  private def isCompilerTask(key: ScopedKey[?]): Boolean =
    key.scope.task.toOption.exists(CompilerTaskKeys.contains)

  private def isTestTask(key: ScopedKey[?]): Boolean =
    key.scope.task.toOption.exists(TestTaskKeys.contains)

  private def testResultLoggerSettings(
    structure: sbt.internal.BuildStructure,
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
      settingWhenDefined(structure, projectRef, configuration, testSelected.key,
        configuration / testSelected / testResultLogger ~= controlledTestResultLogger(
          resultFlowId(scopeId, configuration, testSelected.key),
          taskScreenLogLevel(structure, state, projectRef, configuration, testSelected.key)
        )) ++
      settingWhenDefined(structure, projectRef, configuration, testQuick.key,
        configuration / testQuick / testResultLogger ~= controlledTestResultLogger(
          resultFlowId(scopeId, configuration, testQuick.key),
          taskScreenLogLevel(structure, state, projectRef, configuration, testQuick.key)
        )) ++
      settingWhenDefined(structure, projectRef, configuration, testFull.key,
        configuration / testFull / testResultLogger ~= controlledTestResultLogger(
          resultFlowId(scopeId, configuration, testFull.key),
          taskScreenLogLevel(structure, state, projectRef, configuration, testFull.key)
        ))
  }

  private def settingWhenDefined(
    structure: sbt.internal.BuildStructure,
    projectRef: ProjectRef,
    configuration: Configuration,
    taskKey: AttributeKey[?],
    setting: => Def.Setting[?]
  ): Seq[Def.Setting[?]] =
    val scope = Scope(Select(projectRef), Select(configuration), Select(taskKey), Zero)
    val scopedKey = Def.ScopedKey(scope, testResultLogger.key)
    if structure.data.get(scopedKey).isDefined then Seq(setting) else Nil

  private def controlledTestResultLogger(
    flowId: String,
    screenLevel: Level.Value
  )(configured: TestResultLogger): TestResultLogger =
    if useTeamCityTestResultLogger then silentTestResultLogger
    else if showTestTaskOutput then configured
    else redirectTestResultLogger(configured, tcLogAppender, flowId, screenLevel)

  private def taskScreenLogLevel(
    structure: sbt.internal.BuildStructure,
    state: State,
    projectRef: ProjectRef,
    configuration: Configuration,
    taskKey: AttributeKey[?]
  ): Level.Value =
    val scope = Scope(Select(projectRef), Select(configuration), Select(taskKey), Zero)
    LogManager.getOr(logLevel.key, structure.data, scope, state, Level.Info)

  private def resultFlowId(project: String, configuration: Configuration, taskKey: AttributeKey[?]): String =
    s"$project:${configuration.name}:general:${taskKey.label}"

  private def phaseForTask(task: String): String =
    if ResolverTaskNames.contains(task) then "dependency"
    else s"general:$task"

  private def compilerFlowId(project: String, configuration: String): String =
    s"$project:$configuration:compiler"

}

/** Version-local boundary for the Coursier API; the shared reporter remains free of sbt implementation classes. */
private final class DetailedCoursierLogger(appender: TCLogAppender, projectName: String, configuration: String) extends CacheLogger:
  override def foundLocally(url: String): Unit =
    appender.detailedDependencyFoundLocally(projectName, configuration, url)

  override def downloadingArtifact(url: String): Unit =
    appender.detailedDependencyDownloading(projectName, configuration, url)

  override def downloadLength(url: String, totalLength: Long, alreadyDownloaded: Long, watching: Boolean): Unit =
    appender.detailedDependencyDownloadLength(projectName, configuration, url, totalLength)

  override def downloadedArtifact(url: String, success: Boolean): Unit =
    appender.detailedDependencyDownloaded(projectName, configuration, url, success)
