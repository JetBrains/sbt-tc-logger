// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildEventReporter
import org.jetbrains.teamcity.plugins.sbt.logger.reporting.SbtCompilerInspectionReporter.FilePosition
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal.{SbtPrivateKeys, SbtTestResultLoggerAdapter}
import sbt.util.Level
import sbt.{Def, Reference, Scope, Select, TestResultLogger, Zero}

/** SBT 1 compatibility boundary for APIs that differ from the SBT 2 target. */
object SbtApiAdapter {
  type SessionSettings = _root_.sbt.internal.SessionSettings

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
    new SbtTestResultLoggerAdapter(delegate, buildEventReporter, flowId, screenLevel, reportIfInitializerError)

  def projectScope(project: Reference): Scope = Scope(Select(project), Zero, Zero, Zero)

  def reporterSettings(
    buildEventReporter: SbtBuildEventReporter,
    writer: TeamCityServiceMessageWriter,
    flowId: String,
    ensureCompilationStarted: () => Unit,
    reportCompilerOutput: Boolean
  ): Def.Setting[?] = {
    import _root_.sbt.Keys.compile
    compile / SbtPrivateKeys.compilerReporter := {
      val defaultReporter = (compile / SbtPrivateKeys.compilerReporter).value
      new SbtCompilerProblemReporter(defaultReporter, buildEventReporter, writer, flowId, ensureCompilationStarted, reportCompilerOutput)
    }
  }

  def toFilePosition(position: _root_.xsbti.Position): Option[FilePosition] = {
    val path = position.sourcePath()
    val maybeLine = position.line()
    val line = if (maybeLine.isPresent) maybeLine.get().intValue() else 0
    if (path.isPresent) Some(FilePosition(path.get(), line)) else None
  }

}
