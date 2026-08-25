// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

import java.io.{PrintWriter, StringWriter}
import java.nio.charset.StandardCharsets
import java.util.UUID

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.*
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import sbt.*
import sbt.testing.{NestedTestSelector, OptionalThrowable, Status, TestSelector}
import scala.collection.concurrent.TrieMap

/**
 * Adapts SBT test callbacks to typed TeamCity test messages.
 *
 * This listener only contributes to TeamCity's Tests tab. Ordinary SBT task output and aggregate result-logger
 * output travel through the separate Build Log path.
 */
final class SbtTestReportListener(writer: TeamCityServiceMessageWriter) extends TestReportListener {
  private val startedGroupFlows = TrieMap.empty[String, String]

  override def startGroup(name: String): Unit = {
    val groupFlowId = stableGroupFlowId(name)
    startedGroupFlows.put(name, groupFlowId)
    writer.write(TestSuiteStarted(name, groupFlowId))
  }

  override def testEvent(event: TestEvent): Unit =
    event.detail.foreach(reportSingleTest)

  override def endGroup(name: String, throwable: Throwable): Unit = {
    finishStartedGroup(name) { groupFlowId =>
      writer.write(TestSuiteFinished(name, groupFlowId, Some(TestSuiteFailure(throwable.getMessage, formattedStackTrace(throwable)))))
    }
  }

  override def endGroup(name: String, result: TestResult): Unit =
    finishStartedGroup(name) { groupFlowId => writer.write(TestSuiteFinished(name, groupFlowId)) }

  private def reportSingleTest(event: _root_.sbt.testing.Event): Unit = {
    val testName = qualifiedTestName(event)
    val eventFlowId = startedGroupFlowFor(testName).getOrElse(flowId)
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
    AnsiEscapeSequence.replaceAllIn(writer.toString, "")
  }

  // ANSI styles are useful in an interactive terminal, but TeamCity renders their escaped bytes as literal text.
  private val AnsiEscapeSequence = "\\u001B\\[[0-?]*[ -/]*[@-~]".r

  private def finishStartedGroup(name: String)(writeFinished: String => Unit): Unit =
    startedGroupFlows.remove(name).foreach(writeFinished)

  /**
   * Returns the active test group which owns a test event.
   *
   * Test frameworks are allowed to invoke [[testEvent]] from a worker other than the one that invoked
   * [[startGroup]]. A thread-derived flow therefore detached leaf events from their suite whenever executor
   * scheduling changed. A qualified test name belongs to its exact group or to the most-specific active group
   * which prefixes it. Selecting the longest prefix keeps nested framework suites associated with their inner
   * group rather than an outer one.
   */
  private def startedGroupFlowFor(testName: String): Option[String] =
    startedGroupFlows.iterator.collect {
      case (groupName, groupFlowId) if testName == groupName || testName.startsWith(s"$groupName.") =>
        groupName -> groupFlowId
    }.toSeq.sortBy { case (groupName, _) => -groupName.length }.headOption.map(_._2)

  /**
   * Creates a deterministic, protocol-safe flow ID from an SBT group name.
   *
   * The UUID is name-based (rather than random), so all callbacks for one group share a flow even when the
   * framework moves them across threads. It also avoids placing arbitrary framework-provided group text directly
   * into a TeamCity attribute.
   */
  private def stableGroupFlowId(name: String): String =
    s"teamcity-sbt-test-${UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))}"

  // A framework callback without a matching started group is unexpected, but keeping the legacy thread flow lets
  // us report it without inventing suite ownership.
  private def flowId: String = Thread.currentThread().getId.toString
}
