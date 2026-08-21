// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import _root_.org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildEventReporter
import _root_.org.jetbrains.teamcity.plugins.sbt.logger.reporting.SbtCompilerInspectionReporter.FilePosition
import _root_.org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import _root_.sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal.{SbtPrivateKeys, SbtTestResultLoggerAdapter}
import _root_.sbt.util.Level
import _root_.sbt.{Def, Reference, Scope, Select, TestResultLogger, Zero}
import _root_.xsbti.Problem

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
    SbtPrivateKeys.compilerReporter in compile := {
      val defaultReporter = (SbtPrivateKeys.compilerReporter in compile).value
      new SbtCompilerProblemReporter(defaultReporter, buildEventReporter, writer, flowId, ensureCompilationStarted, reportCompilerOutput)
    }
  }

  def toFilePosition(position: _root_.xsbti.Position): Option[FilePosition] = {
    val path = position.sourcePath()
    val maybeLine = position.line()
    val line = if (maybeLine.isPresent) maybeLine.get().intValue() else 0
    if (path.isPresent) Some(FilePosition(path.get(), line)) else None
  }

  abstract class ReporterAdapter(delegate: _root_.xsbti.Reporter) extends _root_.xsbti.Reporter {
    def delegateLog(problem: Problem): Unit = delegate.log(problem)
  }
}
