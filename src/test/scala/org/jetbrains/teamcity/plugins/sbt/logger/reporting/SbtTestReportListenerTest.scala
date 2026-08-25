// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

import scala.collection.mutable

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.*
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.{TeamCityServiceMessage, TeamCityServiceMessageWriter}
import org.junit.Assert.{assertEquals, assertNotEquals}
import org.junit.Test
import sbt.TestEvent
import sbt.protocol.testing.TestResult
import sbt.testing.{Event, Fingerprint, OptionalThrowable, Status, TestSelector}

class SbtTestReportListenerTest {
  @Test
  def routesWorkerThreadEventsToTheirMostSpecificStartedGroup(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer)

    listener.startGroup("suites.OuterSuite")
    listener.startGroup("suites.OuterSuite.InnerSuite")
    val reportingThread = new Thread(() => listener.testEvent(TestEvent(Seq(success("suites.OuterSuite.InnerSuite.test")))))
    reportingThread.start()
    reportingThread.join()
    listener.endGroup("suites.OuterSuite.InnerSuite", TestResult.Passed)
    listener.endGroup("suites.OuterSuite", TestResult.Passed)

    val messages = writer.messages.toVector
    val outerFlow = messages.collectFirst { case TestSuiteStarted("suites.OuterSuite", flow) => flow }.get
    val innerFlow = messages.collectFirst { case TestSuiteStarted("suites.OuterSuite.InnerSuite", flow) => flow }.get

    assertNotEquals("Nested suites must not share a flow", outerFlow, innerFlow)
    assertNotEquals("A suite flow must not be derived from the worker thread", reportingThread.getId.toString, innerFlow)
    assertEquals(
      Vector(
        TestSuiteStarted("suites.OuterSuite", outerFlow),
        TestSuiteStarted("suites.OuterSuite.InnerSuite", innerFlow),
        TestStarted("suites.OuterSuite.InnerSuite.test", innerFlow),
        TestFinished("suites.OuterSuite.InnerSuite.test", 0L, innerFlow),
        TestSuiteFinished("suites.OuterSuite.InnerSuite", innerFlow),
        TestSuiteFinished("suites.OuterSuite", outerFlow)
      ),
      messages
    )
  }

  private def success(name: String): Event = new Event {
    override def fullyQualifiedName(): String = name
    override def fingerprint(): Fingerprint = null
    override def selector(): TestSelector = new TestSelector(name)
    override def status(): Status = Status.Success
    override def throwable(): OptionalThrowable = new OptionalThrowable
    override def duration(): Long = 0L
  }

  private final class CapturingWriter extends TeamCityServiceMessageWriter {
    private val buffer = mutable.ArrayBuffer.empty[TeamCityServiceMessage]

    def messages: Seq[TeamCityServiceMessage] = buffer.synchronized(buffer.toVector)

    override def write(message: TeamCityServiceMessage): Unit = buffer.synchronized(buffer += message)
  }
}
