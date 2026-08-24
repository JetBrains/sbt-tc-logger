// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

import java.io.{PrintWriter, StringWriter}

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.*
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import sbt.*
import sbt.testing.{NestedTestSelector, OptionalThrowable, Status, TestSelector}

/**
 * Adapts SBT test callbacks to typed TeamCity test messages.
 *
 * This listener only contributes to TeamCity's Tests tab. Ordinary SBT task output and aggregate result-logger
 * output travel through the separate Build Log path.
 */
final class SbtTestReportListener(writer: TeamCityServiceMessageWriter) extends TestReportListener {
  override def startGroup(name: String): Unit =
    writer.write(TestSuiteStarted(name, flowId))

  override def testEvent(event: TestEvent): Unit =
    event.detail.foreach(reportSingleTest)

  override def endGroup(name: String, throwable: Throwable): Unit = {
    writer.write(TestSuiteFinished(name, flowId, Some(TestSuiteFailure(throwable.getMessage, formattedStackTrace(throwable)))))
  }

  override def endGroup(name: String, result: TestResult): Unit =
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
        // `example.ATest` + `testMe` becomes `example.ATest.testMe`.
        // `example.ATest.testMe` + `example.ATest.testMe` remains `example.ATest.testMe`.
        s"${fullyQualifiedNamePrefix(fullyQualifiedName, selector.testName)}${selector.testName}"
      case selector: NestedTestSelector =>
        // `example.OuterSuite`, `InnerSuite`, and `testMe` become `example.OuterSuite.InnerSuite.testMe`.
        // `testMe`, `InnerSuite`, and `testMe` become `InnerSuite.testMe`.
        s"${fullyQualifiedNamePrefix(fullyQualifiedName, selector.testName)}${selector.suiteId}.${selector.testName}"
      case _ =>
        // A `SuiteSelector` event named `example.ATest` remains `example.ATest`.
        fullyQualifiedName
    }
  }

  /**
   * Produces the part of a TeamCity test name that precedes selector-specific names.
   *
   * On 11 February 2014, the original listener always appended [[TestSelector#testName]] to
   * [[sbt.testing.Event#fullyQualifiedName]]. On 10 September 2015, nested suite support added the same
   * unconditional prefix for [[NestedTestSelector]]. That assumption proved invalid on 24 October 2018: JUnit
   * can report its complete test identifier as both `fullyQualifiedName` and `testName`, so concatenating the
   * two produced a duplicated name such as `example.ATest.testMe.example.ATest.testMe`.
   *
   * The equality check was therefore introduced for `TestSelector`. On 4 June 2020, nested-selector handling
   * was corrected to use the same rule: some nested-test frameworks report the leaf test name as the event's
   * fully qualified name. In that case the listener must emit `suiteId.testName`, rather than prefixing it with
   * the already-represented leaf name.
   *
   * Equality only controls whether the fully qualified name is a prefix; it does not mean both selector kinds
   * render identically. A `TestSelector` then renders `testName`, which equals the fully qualified name, whereas
   * a `NestedTestSelector` still adds its `suiteId`.
   */
  private def fullyQualifiedNamePrefix(fullyQualifiedName: String, testName: String): String =
    if (fullyQualifiedName == testName)
      ""
    else
      s"$fullyQualifiedName."

  private def formattedException(throwable: OptionalThrowable): String = {
    if (throwable.isDefined) {
      formattedStackTrace(throwable.get)
    } else {
      ""
    }
  }

  private def formattedStackTrace(throwable: Throwable): String = {
    val writer = new StringWriter
    throwable.printStackTrace(new PrintWriter(writer))
    writer.toString
  }

  private def flowId: String = Thread.currentThread().getId.toString
}
