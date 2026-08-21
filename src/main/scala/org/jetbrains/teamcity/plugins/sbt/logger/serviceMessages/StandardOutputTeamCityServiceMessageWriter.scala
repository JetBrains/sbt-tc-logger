// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages

import java.io.PrintStream

import jetbrains.buildServer.messages.serviceMessages.{MapSerializerUtil, ServiceMessageTypes}

/**
 * Writes TeamCity service messages as protocol lines to a standard output stream.
 *
 * The renderer deliberately controls attribute order because this plugin's integration transcripts treat the
 * complete service-message line as a compatibility contract.
 */
final class StandardOutputTeamCityServiceMessageWriter(output: PrintStream = System.out) extends TeamCityServiceMessageWriter {
  override def write(message: TeamCityServiceMessage): Unit =
    output.println(TeamCityServiceMessageRenderer.render(message))
}

private[serviceMessages] object TeamCityServiceMessageRenderer {
  import TeamCityServiceMessage.*

  def render(message: TeamCityServiceMessage): String = message match {
    case BuildLogMessage(status, text, flowId) =>
      protocolLine(ServiceMessageTypes.MESSAGE, Seq("status" -> status.wireValue) ++ flowId.map("flowId" -> _) ++ Seq("text" -> text))
    case CompilationStarted(compiler, flowId) =>
      protocolLine(ServiceMessageTypes.COMPILATION_STARTED, Seq("compiler" -> compiler, "flowId" -> flowId))
    case CompilationFinished(compiler, flowId) =>
      protocolLine(ServiceMessageTypes.COMPILATION_FINISHED, Seq("compiler" -> compiler, "flowId" -> flowId))
    case BlockOpened(name, flowId) =>
      protocolLine(ServiceMessageTypes.BLOCK_OPENED, Seq("name" -> name, "flowId" -> flowId))
    case BlockClosed(name, flowId) =>
      protocolLine(ServiceMessageTypes.BLOCK_CLOSED, Seq("name" -> name, "flowId" -> flowId))
    case TestSuiteStarted(name, flowId) =>
      protocolLine(ServiceMessageTypes.TEST_SUITE_STARTED, Seq("name" -> name, "flowId" -> flowId))
    case TestSuiteFinished(name, flowId, None) =>
      protocolLine(ServiceMessageTypes.TEST_SUITE_FINISHED, Seq("name" -> name, "flowId" -> flowId))
    case TestSuiteFinished(name, flowId, Some(failure)) =>
      protocolLine(ServiceMessageTypes.TEST_SUITE_FINISHED, Seq("name" -> name, "message" -> failure.message, "details" -> failure.details, "flowId" -> flowId))
    case TestStarted(name, flowId, captureStandardOutput) =>
      protocolLine(ServiceMessageTypes.TEST_STARTED, Seq("name" -> name, "captureStandardOutput" -> captureStandardOutput.toString, "flowId" -> flowId))
    case TestFinished(name, durationMillis, flowId) =>
      protocolLine(ServiceMessageTypes.TEST_FINISHED, Seq("name" -> name, "duration" -> durationMillis.toString, "flowId" -> flowId))
    case TestFailed(name, details, flowId) =>
      protocolLine(ServiceMessageTypes.TEST_FAILED, Seq("name" -> name, "details" -> details, "flowId" -> flowId))
    case TestIgnored(name, flowId) =>
      protocolLine(ServiceMessageTypes.TEST_IGNORED, Seq("name" -> name, "flowId" -> flowId))
    case CompilerInspectionType(id, name, description, category) =>
      protocolLine("inspectionType", Seq("id" -> id, "name" -> name, "description" -> description, "category" -> category))
    case CompilerInspection(typeId, message, file, line, severity) =>
      protocolLine("inspection", Seq("SEVERITY" -> severity.wireValue, "line" -> line.toString, "typeId" -> typeId, "message" -> message, "file" -> file))
  }

  private def protocolLine(name: String, attributes: Seq[(String, String)]): String = {
    val renderedAttributes = attributes.map(renderAttribute).mkString(" ")
    s"##teamcity[$name $renderedAttributes]"
  }

  @inline private def renderAttribute(tuple: Tuple2[String, String]): String =
    renderAttribute(tuple._1, tuple._2)

  @inline private def renderAttribute(key: String, value: String): String = {
    val valueSanitised = MapSerializerUtil.escapeStr(value, MapSerializerUtil.STD_ESCAPER2)
    s"$key='$valueSanitised'"
  }
}
