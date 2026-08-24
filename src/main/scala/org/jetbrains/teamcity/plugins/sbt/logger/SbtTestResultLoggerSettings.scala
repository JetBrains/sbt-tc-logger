// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.SbtApiSupport.*
import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildLogMessageReporter
import sbt.Keys.logLevel
import sbt.internal.LogManager
import sbt.internal.util.AttributeKey
import sbt.util.Level
import sbt.{Configuration, Def, ProjectRef, Scope, Select, State, TestResultLogger, Zero}

/** Installs TeamCity-aware test-result loggers for each supported SBT test-task API. */
final class SbtTestResultLoggerSettings(
  buildLogMessageReporter: => SbtBuildLogMessageReporter,
  useTeamCityTestResultLogger: Boolean,
  showTestTaskOutput: Boolean
) {
  def settings(
    structure: _root_.sbt.internal.BuildStructure,
    state: State,
    projectRef: ProjectRef,
    configurations: Seq[Configuration],
    scopeId: String
  ): Seq[Def.Setting[?]] =
    configurations.foldLeft(Seq.empty[Def.Setting[?]]) { (allSettings, configuration) =>
      allSettings ++ settingsForConfiguration(structure, state, projectRef, configuration, scopeId)
    }

  private def settingsForConfiguration(
    structure: _root_.sbt.internal.BuildStructure,
    state: State,
    projectRef: ProjectRef,
    configuration: Configuration,
    scopeId: String
  ): Seq[Def.Setting[?]] =
    SbtApiAdapter.testResultLoggerTasks.foldLeft(Seq.empty[Def.Setting[?]]) { (settings, task) =>
      val scope = testTaskScope(projectRef, configuration, task.taskKey)
      if (!SbtApiAdapter.testResultLoggerIsDefined(structure, scope))
        settings
      else {
        val flowId = resultFlowId(scopeId, configuration, task.taskKey)
        val screenLevel = taskScreenLogLevel(structure, state, scope)
        settings :+ task.install(configuration, controlledTestResultLogger(flowId, screenLevel))
      }
    }

  private def controlledTestResultLogger(
    flowId: String,
    screenLevel: Level.Value
  )(configured: TestResultLogger): TestResultLogger =
    if (useTeamCityTestResultLogger)
      silentTestResultLogger
    else if (showTestTaskOutput)
      configured
    else
      adaptTestResultLoggerForTeamCity(configured, buildLogMessageReporter, flowId, screenLevel)

  private def taskScreenLogLevel(
    structure: _root_.sbt.internal.BuildStructure,
    state: State,
    scope: Scope
  ): Level.Value =
    LogManager.getOr(logLevel.key, structure.data, scope, state, Level.Info)

  private def testTaskScope(
    projectRef: ProjectRef,
    configuration: Configuration,
    taskKey: AttributeKey[?]
  ): Scope = Scope(Select(projectRef), Select(configuration), Select(taskKey), Zero)

  private def resultFlowId(project: String, configuration: Configuration, taskKey: AttributeKey[?]): String =
    s"$project:${configuration.name}:general:${taskKey.label}"
}

object SbtTestResultLoggerSettings {
  /** Target-specific task binding for a test-result-logger override. */
  private[logger] final case class SbtTestResultLoggerTask(
    taskKey: AttributeKey[?],
    install: (Configuration, TestResultLogger => TestResultLogger) => Def.Setting[?]
  )
}
