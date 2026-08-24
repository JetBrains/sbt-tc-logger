// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import sbt.util.{Level, Logger}
import sbt.TestResultLogger

import java.io.{PrintWriter, StringWriter}

/**
 * Adapts the human-readable output of an SBT [[sbt.TestResultLogger]] to TeamCity's Build Log.
 *
 * SBT invokes a test-result logger after it has run tests. The logger normally writes aggregate output such as
 * framework summaries, total counts, and failed-suite names to the `Logger` SBT supplies. This adapter returns a
 * logger that invokes `delegate` with the same test results and task name, but replaces that supplied logger with
 * one that routes its messages through `buildEventReporter` in `flowId`.
 *
 * For example, the normal SBT result summary can be redirected to TeamCity while retaining SBT's failure semantics:
 * {{{
 * val teamCityResultLogger = SbtTestResultLoggerAdapter(
 *   TestResultLogger.Default,
 *   buildEventReporter,
 *   flowId = "example/Test/test",
 *   screenLevel = Level.Info,
 *   reportIfInitializerError = initializerErrorReporter.reportIfInitializerError
 * )
 *
 * Test / testResultLogger := teamCityResultLogger
 * }}}
 *
 * For example, a `delegate` call to `log.info("Passed: Total 12, Failed 0")` becomes a normal Build Log message
 * in that flow. A call to `log.error("Failed: Total 12, Failed 1")` becomes an error Build Log message and also
 * invokes `reportIfInitializerError` with the same text.
 *
 * The returned logger preserves the delegate's handling of test success and failure. It filters ordinary messages
 * below `screenLevel`, associates Build Log messages with the supplied flow, and passes error output to
 * `reportIfInitializerError` so the initializer-error fallback can publish its structured failure.
 *
 * This adapter does not inspect or transform per-suite results, produce `testStarted`/`testFailed`/`testFinished`
 * service messages, or determine the task result and process exit code.
 * [[org.jetbrains.teamcity.plugins.sbt.logger.reporting.SbtTestReportListener]] owns structured Tests-tab events;
 * SBT and `delegate` retain result and failure semantics.
 */
object SbtTestResultLoggerAdapter {
  def apply(
    delegate: TestResultLogger,
    buildEventReporter: SbtBuildEventReporter,
    flowId: String,
    screenLevel: Level.Value,
    reportIfInitializerError: (String, String) => Unit
  ): TestResultLogger =
    // Keep `results` inferred: until sbt/sbt#9655 is merged and released, SBT 2 makes Tests.Output
    // private[sbt], so naming that type here fails outside package sbt. Once a released 2.0.x contains
    // the fix, updating the target cross-build version permits direct use of Tests.Output; this form
    // remains compatible with older SBT 2.0.x runtimes because exposing Tests.Output does not change its JVM signature.
    TestResultLogger { (_, results, taskName) =>
      val directTeamCityLogger = new DirectTeamCityLogger(buildEventReporter, flowId, screenLevel, reportIfInitializerError)
      delegate.run(directTeamCityLogger, results, taskName)
    }

  private final class DirectTeamCityLogger(
    buildEventReporter: SbtBuildEventReporter,
    flowId: String,
    screenLevel: Level.Value,
    reportIfInitializerError: (String, String) => Unit
  ) extends Logger {
    override def trace(error: => Throwable): Unit = {
      val buffer = new StringWriter
      error.printStackTrace(new PrintWriter(buffer))
      val message = buffer.toString
      reportIfInitializerError(message, flowId)
      buildEventReporter.log(Level.Error, message, flowId)
    }

    override def success(message: => String): Unit =
      if (isEnabled(Level.Info)) buildEventReporter.log(Level.Info, message, flowId)

    override def log(level: Level.Value, message: => String): Unit = {
      val text = message
      if (Level.Error.equals(level)) reportIfInitializerError(text, flowId)
      if (isEnabled(level)) buildEventReporter.log(level, text, flowId)
    }

    private def isEnabled(level: Level.Value): Boolean = level.compare(screenLevel) >= 0
  }
}
