/*
 * Copyright 2013-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

package sbt.jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.TCCompilerReporter.FilePosition
import jetbrains.buildServer.sbtlogger.{TCCompilerReporter, TCLogAppender}
import sbt.util.{Level, Logger}
import sbt.{Def, Reference, Scope, Select, TestResultLogger, Tests, Zero}
import xsbti.Problem

import java.io.{PrintWriter, StringWriter}

object apiAdapter {

  type SessionSettings = sbt.internal.SessionSettings

  /** Suppresses SBT's final test-result text without suppressing its failed/error result exception. */
  val silentTestResultLogger: TestResultLogger = TestResultLogger.Default.copy(
    printSummary = TestResultLogger.Null,
    printStandard = TestResultLogger.Null,
    printFailures = TestResultLogger.Null,
    printNoTests = TestResultLogger.Null
  )

  /** Runs a configured result logger on a direct TeamCity sink, independently of the test task's screen appender. */
  def redirectTestResultLogger(delegate: TestResultLogger, appender: TCLogAppender, flowId: String): TestResultLogger =
    new TestResultLogger {
      override def run(log: Logger, results: Tests.Output, taskName: String): Unit =
        delegate.run(new DirectTeamCityLogger(appender, flowId), results, taskName)
    }

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

  private final class DirectTeamCityLogger(appender: TCLogAppender, flowId: String) extends Logger {
    override def trace(error: => Throwable): Unit = {
      val buffer = new StringWriter
      error.printStackTrace(new PrintWriter(buffer))
      appender.log(Level.Error, buffer.toString, flowId)
    }

    override def success(message: => String): Unit = appender.log(Level.Info, message, flowId)

    override def log(level: Level.Value, message: => String): Unit = appender.log(level, message, flowId)
  }
}
