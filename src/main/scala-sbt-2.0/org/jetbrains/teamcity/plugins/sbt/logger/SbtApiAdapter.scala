// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.SbtTestResultLoggerSettings.SbtTestResultLoggerTask
import sbt./
import sbt.Def
import sbt.Keys.{test, testFull, testOnly, testQuick, testResultLogger, testSelected}
import sbt.internal.util.AttributeKey
import sbt.{Configuration, Scope, TestResultLogger}

/** SBT 2 compatibility boundary for APIs that differ from the SBT 1 target. */
object SbtApiAdapter {
  inline def uncached[T](inline value: T): T = Def.uncached(value)

  // SBT 2 can materialize `configuration / task / testResultLogger` as a ScopedKey, but SBT 1's equivalent
  // expression ends as a SettingKey and has no generic public path to apply `~=` from shared code. Keep these
  // typed setting transformations target-specific; the shared builder owns the task iteration and policy.
  val testResultLoggerTasks: Seq[SbtTestResultLoggerTask] = Seq(
    SbtTestResultLoggerTask(test.key, (configuration, transform) =>
      configuration / test / testResultLogger ~= transform),
    SbtTestResultLoggerTask(testOnly.key, (configuration, transform) =>
      configuration / testOnly / testResultLogger ~= transform),
    SbtTestResultLoggerTask(testSelected.key, (configuration, transform) =>
      configuration / testSelected / testResultLogger ~= transform),
    SbtTestResultLoggerTask(testQuick.key, (configuration, transform) =>
      configuration / testQuick / testResultLogger ~= transform),
    SbtTestResultLoggerTask(testFull.key, (configuration, transform) =>
      configuration / testFull / testResultLogger ~= transform)
  )

  val testTaskKeys: Set[AttributeKey[?]] = testResultLoggerTasks.map(_.taskKey).toSet

  def testResultLoggerIsDefined(
    structure: _root_.sbt.internal.BuildStructure,
    scope: Scope
  ): Boolean = structure.data.get(Def.ScopedKey(scope, testResultLogger.key)).isDefined
}
