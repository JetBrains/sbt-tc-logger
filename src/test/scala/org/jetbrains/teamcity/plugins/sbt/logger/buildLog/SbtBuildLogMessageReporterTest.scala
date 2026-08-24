package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.{BuildLogMessage, BuildLogStatus}
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.{TeamCityServiceMessage, TeamCityServiceMessageWriter}
import org.junit.Assert.assertEquals
import org.junit.Test
import sbt.util.Level

class SbtBuildLogMessageReporterTest {
  @Test
  def preservesStatusFlowAndPrefixesEveryMessageLine(): Unit = {
    val writer = new CapturingWriter
    val reporter = new SbtBuildLogMessageReporter(writer)

    reporter.log(Level.Warn, "first\nsecond", "task-flow")

    assertEquals(
      BuildLogMessage(BuildLogStatus.Warning, "[warn] first\n[warn] second", Some("task-flow")),
      writer.message
    )
  }

  private final class CapturingWriter extends TeamCityServiceMessageWriter {
    var message: TeamCityServiceMessage = _

    override def write(value: TeamCityServiceMessage): Unit = message = value
  }
}
