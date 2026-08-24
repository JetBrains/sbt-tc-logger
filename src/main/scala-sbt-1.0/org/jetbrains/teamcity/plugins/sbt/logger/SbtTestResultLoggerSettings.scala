// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.SbtApiSupport.*
import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildLogMessageReporter
import sbt.Keys.*
import sbt.internal.LogManager
import sbt.internal.util.AttributeKey
import sbt.{Configuration, Def, ProjectRef, Scope, Select, State, TestResultLogger, Zero}
import sbt.util.Level

/** Installs TeamCity-aware test-result loggers for the SBT 1 test-task API. */
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
    else adaptTestResultLoggerForTeamCity(configured, buildLogMessageReporter, flowId, screenLevel)

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
}
