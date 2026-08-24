// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.{BuildLogMessage, BuildLogStatus}
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter

/** Converts ordinary SBT log output into TeamCity Build Log messages. */
final class SbtBuildLogMessageReporter(writer: TeamCityServiceMessageWriter) {
  def log(level: _root_.sbt.Level.Value, message: => String, flowId: String): Unit =
    log(statusFor(level), level.toString, message, Some(flowId))

  def log(level: String, message: => String, flowId: String): Unit =
    log(statusFor(level), level, message, Some(flowId))

  private[buildLog] def logUngrouped(level: _root_.sbt.Level.Value, message: => String): Unit =
    log(statusFor(level), level.toString, message, None)

  private def log(status: BuildLogStatus, level: String, message: => String, flowId: Option[String]): Unit = {
    val text = message
    writer.write(BuildLogMessage(status, withLevelPrefix(level, text), flowId))
  }

  private def statusFor(level: _root_.sbt.Level.Value): BuildLogStatus = level match {
    case _root_.sbt.Level.Error => BuildLogStatus.Error
    case _root_.sbt.Level.Warn => BuildLogStatus.Warning
    case _ => BuildLogStatus.Normal
  }

  private def statusFor(level: String): BuildLogStatus = level match {
    case "ERROR" => BuildLogStatus.Error
    case "WARN" => BuildLogStatus.Warning
    case _ => BuildLogStatus.Normal
  }

  private def withLevelPrefix(level: String, text: String): String = {
    val prefix = s"[${level.toLowerCase}] "
    text.split("\\r?\\n", -1).map(prefix + _).mkString("\n")
  }
}
