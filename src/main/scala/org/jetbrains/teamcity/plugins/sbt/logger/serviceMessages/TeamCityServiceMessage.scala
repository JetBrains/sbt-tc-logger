// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages

/**
 * A typed TeamCity service message emitted by the SBT TeamCity logger.
 *
 * The cases describe the protocol messages this plugin supports. They are intentionally independent of SBT, Zinc,
 * and Coursier so any integration can construct messages and send them through [[TeamCityServiceMessageWriter]].
 */
sealed trait TeamCityServiceMessage

object TeamCityServiceMessage {

  sealed abstract class BuildLogStatus(private[serviceMessages] val wireValue: String)

  object BuildLogStatus {
    case object Normal extends BuildLogStatus("NORMAL")
    case object Warning extends BuildLogStatus("WARNING")
    case object Error extends BuildLogStatus("ERROR")
  }

  sealed abstract class InspectionSeverity(private[serviceMessages] val wireValue: String)

  object InspectionSeverity {
    case object Info extends InspectionSeverity("INFO")
    case object Warning extends InspectionSeverity("WARNING")
    case object Error extends InspectionSeverity("ERROR")
  }

  final case class BuildLogMessage(status: BuildLogStatus, text: String, flowId: Option[String]) extends TeamCityServiceMessage
  final case class CompilationStarted(compiler: String, flowId: String) extends TeamCityServiceMessage
  final case class CompilationFinished(compiler: String, flowId: String) extends TeamCityServiceMessage
  final case class BlockOpened(name: String, flowId: String) extends TeamCityServiceMessage
  final case class BlockClosed(name: String, flowId: String) extends TeamCityServiceMessage
  final case class TestSuiteStarted(name: String, flowId: String) extends TeamCityServiceMessage
  final case class TestSuiteFinished(name: String, flowId: String, failure: Option[TestSuiteFailure] = None) extends TeamCityServiceMessage
  final case class TestSuiteFailure(message: String, details: String)
  final case class TestStarted(name: String, flowId: String, captureStandardOutput: Boolean = true) extends TeamCityServiceMessage
  final case class TestFinished(name: String, durationMillis: Long, flowId: String) extends TeamCityServiceMessage
  final case class TestFailed(name: String, details: String, flowId: String) extends TeamCityServiceMessage
  final case class TestIgnored(name: String, flowId: String) extends TeamCityServiceMessage
  final case class CompilerInspectionType(id: String, name: String, description: String, category: String) extends TeamCityServiceMessage
  final case class CompilerInspection(
    typeId: String,
    message: String,
    file: String,
    line: Int,
    severity: InspectionSeverity
  ) extends TeamCityServiceMessage
}
