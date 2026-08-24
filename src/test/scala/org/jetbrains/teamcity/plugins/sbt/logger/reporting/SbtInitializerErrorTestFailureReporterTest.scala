package org.jetbrains.teamcity.plugins.sbt.logger.reporting

import scala.collection.mutable

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.*
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.{TeamCityServiceMessage, TeamCityServiceMessageWriter}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

class SbtInitializerErrorTestFailureReporterTest {
  @Test def reportsLegacyInitializerErrorWithSuiteNameAndCompleteDetails(): Unit = {
    val suiteName = "example.LegacySuite"
    val details =
      """Could not run test example.LegacySuite: java.lang.ExceptionInInitializerError
        |\tat example.LegacySuite.<init>(LegacySuite.scala:7)
        |Caused by: java.lang.IllegalArgumentException: fixture failure""".stripMargin

    assertInitializerFailure(suiteName, details, "fallback-flow")
  }

  @Test def reportsConstructorFrameInitializerErrorWithSuiteNameAndCompleteDetails(): Unit = {
    val suiteName = "example.ModernSuite"
    val details =
      """java.lang.ExceptionInInitializerError
        |  at example.ModernSuite.<init>(ModernSuite.scala:11)
        |Caused by: java.lang.IllegalStateException: fixture failure""".stripMargin

    assertInitializerFailure(suiteName, details, "fallback-flow")
  }

  @Test def ignoresUnrelatedAndUnidentifiableInitializerErrorText(): Unit = {
    val writer = new CapturingWriter
    val reporter = new SbtInitializerErrorTestFailureReporter(new SbtTestReportListener(writer))

    reporter.reportIfInitializerError(
      "Could not run test example.UnrelatedSuite: java.lang.IllegalStateException",
      "fallback-flow"
    )
    reporter.reportIfInitializerError(
      """java.lang.ExceptionInInitializerError
        |  at example.UnidentifiableSuite.run(UnidentifiableSuite.scala:7)""".stripMargin,
      "fallback-flow"
    )

    assertTrue("Only a recognised initializer-error suite must produce a test failure", writer.messages.isEmpty)
  }

  private def assertInitializerFailure(suiteName: String, details: String, fallbackFlowId: String): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer)
    val reporter = new SbtInitializerErrorTestFailureReporter(listener)
    val suiteFlowId = Thread.currentThread().getId.toString

    listener.startGroup(suiteName)
    reporter.reportIfInitializerError(details, fallbackFlowId)

    assertEquals(Seq(
      TestSuiteStarted(suiteName, suiteFlowId),
      TestFailed(suiteName, details, suiteFlowId),
      TestSuiteFinished(suiteName, suiteFlowId, Some(TestSuiteFailure("ExceptionInInitializerError", details)))
    ), writer.messages.toSeq)
  }

  private final class CapturingWriter extends TeamCityServiceMessageWriter {
    val messages: mutable.ArrayBuffer[TeamCityServiceMessage] = mutable.ArrayBuffer.empty

    override def write(message: TeamCityServiceMessage): Unit = messages += message
  }
}
