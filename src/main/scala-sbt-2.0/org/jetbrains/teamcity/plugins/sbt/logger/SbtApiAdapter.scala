// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.SbtTestResultLoggerSettings.SbtTestResultLoggerTask
import org.jetbrains.teamcity.plugins.sbt.logger.reporting.SbtTestReportListener
import sbt./
import sbt.Def
import sbt.Keys.{logLevel, test, testFull, testListeners, testOnly, testQuick, testResultLogger, testSelected, update}
import sbt.internal.util.AttributeKey
import sbt.{Configuration, Extracted, ProjectRef, Scope, TestResultLogger}
import sbt.util.Level

/** SBT 2 compatibility boundary for APIs that differ from the SBT 1 target. */
object SbtApiAdapter {
  final case class SbtTestReportListenerTask(
    taskKey: AttributeKey[?],
    install: (Configuration, () => SbtTestReportListener) => Def.Setting[?]
  )

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

  val testReportListenerTasks: Seq[SbtTestReportListenerTask] = Seq(
    SbtTestReportListenerTask(test.key, (configuration, create) =>
      configuration / test / testListeners := uncached {
        val configured = (configuration / test / testListeners).value
        configured.filterNot(SbtTestReportListener.isPluginOwned) :+ create()
      }),
    SbtTestReportListenerTask(testOnly.key, (configuration, create) =>
      configuration / testOnly / testListeners := uncached {
        val configured = (configuration / testOnly / testListeners).value
        configured.filterNot(SbtTestReportListener.isPluginOwned) :+ create()
      }),
    SbtTestReportListenerTask(testSelected.key, (configuration, create) =>
      configuration / testSelected / testListeners := uncached {
        val configured = (configuration / testSelected / testListeners).value
        configured.filterNot(SbtTestReportListener.isPluginOwned) :+ create()
      }),
    SbtTestReportListenerTask(testQuick.key, (configuration, create) =>
      configuration / testQuick / testListeners := uncached {
        val configured = (configuration / testQuick / testListeners).value
        configured.filterNot(SbtTestReportListener.isPluginOwned) :+ create()
      }),
    SbtTestReportListenerTask(testFull.key, (configuration, create) =>
      configuration / testFull / testListeners := uncached {
        val configured = (configuration / testFull / testListeners).value
        configured.filterNot(SbtTestReportListener.isPluginOwned) :+ create()
      })
  )

  val testTaskKeys: Set[AttributeKey[?]] = testResultLoggerTasks.map(_.taskKey).toSet

  def testResultLoggerIsDefined(
    structure: _root_.sbt.internal.BuildStructure,
    scope: Scope
  ): Boolean = structure.data.get(Def.ScopedKey(scope, testResultLogger.key)).isDefined

  /** SBT 2 always resolves dependencies with Coursier. */
  def isCoursierEnabled(_extracted: Extracted, _projectRef: ProjectRef): Boolean = true

  def updateLogLevel(
    extracted: Extracted,
    projectRef: ProjectRef,
    configuration: Option[Configuration]
  ): Option[Level.Value] =
    configuration match {
      case Some(config) => extracted.getOpt(projectRef / config / update / logLevel)
      case None => extracted.getOpt(projectRef / update / logLevel)
    }
}
