// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.{BuildLogMessage, BuildLogStatus}
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import sbt.util.Level

/** Converts ordinary SBT log output into TeamCity Build Log messages. */
final class SbtBuildLogMessageReporter(writer: TeamCityServiceMessageWriter) {

  def log(level: Level.Value, message: => String, flowId: String): Unit =
    log(statusFor(level), level, message, Some(flowId))

  private[buildLog] def logUngrouped(level: Level.Value, message: => String): Unit =
    log(statusFor(level), level, message, None)

  private def log(status: BuildLogStatus, level: Level.Value, message: => String, flowId: Option[String]): Unit = {
    val text = message
    writer.write(BuildLogMessage(status, withLevelPrefix(level, text), flowId))
  }

  private def statusFor(level: Level.Value): BuildLogStatus = level match {
    case Level.Error => BuildLogStatus.Error
    case Level.Warn => BuildLogStatus.Warning
    case _ => BuildLogStatus.Normal
  }

  private def withLevelPrefix(level: Level.Value, text: String): String = {
    val prefix = s"[${level.toString.toLowerCase}] "
    text.split("\\r?\\n", -1).map(prefix + _).mkString("\n")
  }
}
