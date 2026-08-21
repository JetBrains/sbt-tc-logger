package org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages

import java.io.{ByteArrayOutputStream, PrintStream}

import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage._
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

class StandardOutputTeamCityServiceMessageWriterTest {
  @Test
  def writesEverySupportedMessageInStableProtocolOrder(): Unit = {
    val messages = Seq(
      BuildLogMessage(BuildLogStatus.Warning, "warn", Some("flow")),
      BuildLogMessage(BuildLogStatus.Normal, "ungrouped", None),
      CompilationStarted("Scala compiler", "flow"),
      CompilationFinished("Scala compiler", "flow"),
      BlockOpened("Dependency resolution", "flow"),
      BlockClosed("Dependency resolution", "flow"),
      TestSuiteStarted("suite", "flow"),
      TestSuiteFinished("suite", "flow"),
      TestSuiteFinished("broken-suite", "flow", Some(TestSuiteFailure("boom", "details"))),
      TestStarted("suite.test", "flow"),
      TestFinished("suite.test", Long.MaxValue, "flow"),
      TestFailed("suite.test", "details", "flow"),
      TestIgnored("suite.test", "flow"),
      CompilerInspectionType("SbtCompileProblem", "sbt compile problem", "Compile problems", "Compile problems"),
      CompilerInspection("SbtCompileProblem", "problem", "/tmp/Test.scala", 7, InspectionSeverity.Warning)
    )

    val lines = write(messages)

    assertEquals(Vector(
      "##teamcity[message status='WARNING' flowId='flow' text='warn']",
      "##teamcity[message status='NORMAL' text='ungrouped']",
      "##teamcity[compilationStarted compiler='Scala compiler' flowId='flow']",
      "##teamcity[compilationFinished compiler='Scala compiler' flowId='flow']",
      "##teamcity[blockOpened name='Dependency resolution' flowId='flow']",
      "##teamcity[blockClosed name='Dependency resolution' flowId='flow']",
      "##teamcity[testSuiteStarted name='suite' flowId='flow']",
      "##teamcity[testSuiteFinished name='suite' flowId='flow']",
      "##teamcity[testSuiteFinished name='broken-suite' message='boom' details='details' flowId='flow']",
      "##teamcity[testStarted name='suite.test' captureStandardOutput='true' flowId='flow']",
      "##teamcity[testFinished name='suite.test' duration='9223372036854775807' flowId='flow']",
      "##teamcity[testFailed name='suite.test' details='details' flowId='flow']",
      "##teamcity[testIgnored name='suite.test' flowId='flow']",
      "##teamcity[inspectionType id='SbtCompileProblem' name='sbt compile problem' description='Compile problems' category='Compile problems']",
      "##teamcity[inspection SEVERITY='WARNING' line='7' typeId='SbtCompileProblem' message='problem' file='/tmp/Test.scala']"
    ), lines)
    lines.foreach(line => assertTrue(s"TeamCity parser rejected: $line", ServiceMessage.parse(line) != null))
  }

  @Test
  def escapesProtocolValuesWithoutChangingAttributeOrder(): Unit = {
    val line = write(Seq(BuildLogMessage(BuildLogStatus.Error, "a|'[]\n", Some("flow")))).head

    assertEquals("##teamcity[message status='ERROR' flowId='flow' text='a|||'|[|]|n']", line)
  }

  private def write(messages: Seq[TeamCityServiceMessage]): Vector[String] = {
    val bytes = new ByteArrayOutputStream
    val writer = new StandardOutputTeamCityServiceMessageWriter(new PrintStream(bytes))
    messages.foreach(writer.write)
    bytes.toString("UTF-8").split("\\r?\\n").filter(_.nonEmpty).toVector
  }
}
