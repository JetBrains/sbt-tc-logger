// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import sbt.util.{Level, Logger}
import sbt.TestResultLogger

import java.io.{PrintWriter, StringWriter}

/** Runs an SBT test-result logger against the TeamCity Build Log reporter. */
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
