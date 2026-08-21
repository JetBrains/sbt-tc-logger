// Copyright © 2013–2026 JetBrains s.r.o.
package sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal

import _root_.org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildEventReporter
import sbt.util.{Level, Logger}
import sbt.{TestResultLogger, Tests}

import java.io.{PrintWriter, StringWriter}

/** Runs an SBT test-result logger against the TeamCity Build Log reporter. */
final class SbtTestResultLoggerAdapter(
  delegate: TestResultLogger,
  buildEventReporter: SbtBuildEventReporter,
  flowId: String,
  screenLevel: Level.Value,
  reportIfInitializerError: (String, String) => Unit
) extends TestResultLogger {
  // SBT 2 makes Tests.Output private[sbt], so this override must remain in an sbt.* package.
  // The code is otherwise source-compatible with SBT 1; split it back into target sources if the APIs diverge.
  override def run(log: Logger, results: Tests.Output, taskName: String): Unit = {
    val directTeamCityLogger = new SbtTestResultLoggerAdapter.DirectTeamCityLogger(buildEventReporter, flowId, screenLevel, reportIfInitializerError)
    delegate.run(directTeamCityLogger, results, taskName)
  }
}

object SbtTestResultLoggerAdapter {

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
