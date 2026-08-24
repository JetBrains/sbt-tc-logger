// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.SbtTestResultLoggerSettings.SbtTestResultLoggerTask
import sbt.Keys.{logLevel, test, testOnly, testQuick, testResultLogger, update, useCoursier}
import sbt.internal.util.AttributeKey
import sbt.{Configuration, Extracted, Global, ProjectRef, Scope, TestResultLogger}
import sbt.util.Level

/** SBT 1 compatibility boundary for APIs that differ from the SBT 2 target. */
object SbtApiAdapter {
  /** SBT 1 has no build-wide task-result cache to opt out of. */
  def uncached[T](value: T): T = value

  val testResultLoggerTasks: Seq[SbtTestResultLoggerTask] = Seq(
    SbtTestResultLoggerTask(test.key, (configuration, transform) =>
      configuration / test / testResultLogger ~= transform),
    SbtTestResultLoggerTask(testOnly.key, (configuration, transform) =>
      configuration / testOnly / testResultLogger ~= transform),
    SbtTestResultLoggerTask(testQuick.key, (configuration, transform) =>
      configuration / testQuick / testResultLogger ~= transform)
  )

  val testTaskKeys: Set[AttributeKey[?]] = testResultLoggerTasks.map(_.taskKey).toSet

  def testResultLoggerIsDefined(
    structure: _root_.sbt.internal.BuildStructure,
    scope: Scope
  ): Boolean = structure.data.get(scope, testResultLogger.key).isDefined

  def isCoursierEnabled(extracted: Extracted, projectRef: ProjectRef): Boolean =
    extracted.getOpt(projectRef / useCoursier)
      .orElse(extracted.getOpt(Global / useCoursier))
      .getOrElse(false)

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
