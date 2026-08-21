// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import _root_.org.jetbrains.teamcity.plugins.sbt.logger.TCCompilerReporter.FilePosition
import _root_.org.jetbrains.teamcity.plugins.sbt.logger.{TCCompilerReporter, TCLogAppender}
import sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal.{RedirectTestResultLogger, Unhide}
import sbt.util.Level
import sbt.{Def, Reference, Scope, Select, TestResultLogger, Zero}
import xsbti.Problem

object apiAdapter {

  type SessionSettings = sbt.internal.SessionSettings

  /** Suppresses SBT's final test-result text without suppressing its failed/error result exception. */
  val silentTestResultLogger: TestResultLogger = TestResultLogger.Default.copy(
    printSummary = TestResultLogger.Null,
    printStandard = TestResultLogger.Null,
    printFailures = TestResultLogger.Null,
    printNoTests = TestResultLogger.Null
  )

  /** Runs a configured result logger on a filtered TeamCity sink, independently of the test task's screen appender. */
  def redirectTestResultLogger(
    delegate: TestResultLogger,
    appender: TCLogAppender,
    flowId: String,
    screenLevel: Level.Value
  ): TestResultLogger =
    new RedirectTestResultLogger(delegate, appender, flowId, screenLevel)

  def projectScope(project: Reference): Scope = Scope(Select(project), Zero, Zero, Zero)

  def reporterSettings(
    tcLogAppender: TCLogAppender,
    flowId: String,
    ensureCompilationStarted: () => Unit,
    reportCompilerOutput: Boolean
  ): Def.Setting[?] = {
    import sbt.Keys.compile
    Unhide.compilerReporter in compile := {
      val defaultReporter = (Unhide.compilerReporter in compile).value
      new TCCompilerReporter(defaultReporter, tcLogAppender, flowId, ensureCompilationStarted, reportCompilerOutput)
    }
  }

  def toFilePosition(position: xsbti.Position): Option[FilePosition] = {
    val path = position.sourcePath()
    val maybeLine = position.line()
    val line = if (maybeLine.isPresent) maybeLine.get().intValue() else 0
    if (path.isPresent) Some(FilePosition(path.get(), line))
    else None
  }

  abstract class ReporterAdapter(delegate: xsbti.Reporter) extends xsbti.Reporter {
    def delegateLog(problem: Problem): Unit = {
      delegate.log(problem)
    }
  }

}
