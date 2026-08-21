// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

import java.io.{PrintWriter, StringWriter}

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage._
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import _root_.sbt._
import _root_.sbt.testing.{NestedTestSelector, OptionalThrowable, Status, TestSelector}

/**
 * Adapts SBT test callbacks to typed TeamCity test messages.
 *
 * This listener only contributes to TeamCity's Tests tab. Ordinary SBT task output and aggregate result-logger
 * output travel through the separate Build Log path.
 */
final class SbtTestReportListener(writer: TeamCityServiceMessageWriter) extends TestReportListener {
  def startGroup(name: String): Unit =
    writer.write(TestSuiteStarted(name, flowId))

  def testEvent(event: TestEvent): Unit =
    event.detail.foreach(reportSingleTest)

  def endGroup(name: String, throwable: Throwable): Unit = {
    val details = throwable.getStackTrace
    writer.write(TestSuiteFinished(name, flowId, Some(TestSuiteFailure(throwable.getMessage, s"$details"))))
  }

  def endGroup(name: String, result: TestResult): Unit =
    writer.write(TestSuiteFinished(name, flowId))

  private def reportSingleTest(event: _root_.sbt.testing.Event): Unit = {
    val testName = qualifiedTestName(event)
    val eventFlowId = flowId
    writer.write(TestStarted(testName, eventFlowId))

    event.status match {
      case Status.Success =>
      case Status.Error | Status.Failure =>
        writer.write(TestFailed(testName, formattedException(event.throwable), eventFlowId))
      case Status.Skipped | Status.Ignored | Status.Pending | Status.Canceled =>
        writer.write(TestIgnored(testName, eventFlowId))
    }

    writer.write(TestFinished(testName, event.duration, eventFlowId))
  }

  private def qualifiedTestName(event: _root_.sbt.testing.Event): String = {
    val fullyQualifiedName = event.fullyQualifiedName
    event.selector match {
      case selector: TestSelector =>
        if (fullyQualifiedName == selector.testName()) fullyQualifiedName
        else fullyQualifiedName + "." + selector.testName
      case selector: NestedTestSelector =>
        val prefix = if (fullyQualifiedName == selector.testName()) "" else fullyQualifiedName + "."
        prefix + selector.suiteId + "." + selector.testName
      case _ => fullyQualifiedName
    }
  }

  private def formattedException(throwable: OptionalThrowable): String = {
    if (throwable.isDefined) {
      val writer = new StringWriter
      throwable.get.printStackTrace(new PrintWriter(writer))
      writer.toString
    } else ""
  }

  private def flowId: String = Thread.currentThread().getId.toString
}
