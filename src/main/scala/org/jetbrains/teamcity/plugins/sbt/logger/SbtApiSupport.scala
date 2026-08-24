// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.{SbtBuildEventReporter, SbtTestResultLoggerAdapter}
import org.jetbrains.teamcity.plugins.sbt.logger.reporting.SbtCompilerInspectionReporter.FilePosition
import sbt.util.Level
import sbt.{Reference, Scope, Select, TestResultLogger, Zero}

/** SBT APIs whose contract is shared by the supported SBT 1 and SBT 2 targets. */
object SbtApiSupport {
  type SessionSettings = sbt.internal.SessionSettings

  val silentTestResultLogger: TestResultLogger = TestResultLogger.Default.copy(
    printSummary = TestResultLogger.Null,
    printStandard = TestResultLogger.Null,
    printFailures = TestResultLogger.Null,
    printNoTests = TestResultLogger.Null
  )

  def adaptTestResultLoggerForTeamCity(
    delegate: TestResultLogger,
    buildEventReporter: SbtBuildEventReporter,
    flowId: String,
    screenLevel: Level.Value,
    reportIfInitializerError: (String, String) => Unit
  ): TestResultLogger =
    SbtTestResultLoggerAdapter(delegate, buildEventReporter, flowId, screenLevel, reportIfInitializerError)

  def projectScope(project: Reference): Scope = Scope(Select(project), Zero, Zero, Zero)

  def toFilePosition(position: xsbti.Position): Option[FilePosition] = {
    val path = position.sourcePath()
    val maybeLine = position.line()
    val line = if (maybeLine.isPresent) maybeLine.get().intValue() else 0
    if (path.isPresent) Some(FilePosition(path.get(), line)) else None
  }
}
