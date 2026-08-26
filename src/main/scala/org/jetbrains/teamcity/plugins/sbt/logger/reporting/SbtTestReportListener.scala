// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

import java.io.{PrintWriter, StringWriter}
import java.nio.charset.StandardCharsets
import java.util.UUID

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.*
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import sbt.*
import sbt.testing.{NestedTestSelector, OptionalThrowable, Status, TestSelector}
import scala.collection.mutable

/**
 * Adapts SBT test callbacks to typed TeamCity test messages.
 *
 * This listener only contributes to TeamCity's Tests tab. Ordinary SBT task output and aggregate result-logger
 * output travel through the separate Build Log path.
 *
 * SBT de-duplicates top-level tests by name within one test task and brackets each runner invocation with
 * `startGroup(name)` and one `endGroup(name, ...)`. Different named groups may run concurrently. The callback API
 * carries no task, project, parent-suite, or invocation identity, so the plugin creates a separate listener for
 * every concrete project/configuration/test-task evaluation. An overlapping same-name group which still reaches
 * one listener is inherently ambiguous: both starts are closed as failed and the name is quarantined until their
 * end callbacks arrive.
 */
final class SbtTestReportListener private (
  writer: TeamCityServiceMessageWriter,
  flowNamespace: String,
  evaluationOrdinal: Long,
  private val ownerToken: AnyRef
) extends TestReportListener {
  /** Retains the original constructor for integrations which instantiate the listener directly. */
  def this(writer: TeamCityServiceMessageWriter) = this(
    writer,
    "legacy-unscoped-listener",
    SbtTestReportListener.nextEvaluationOrdinal("legacy-unscoped-listener"),
    null
  )

  /** Allows integrations to provide a stable namespace without marking their listener as plugin-owned. */
  def this(writer: TeamCityServiceMessageWriter, flowNamespace: String) =
    this(writer, flowNamespace, SbtTestReportListener.nextEvaluationOrdinal(flowNamespace), null)

  private[reporting] def this(
    writer: TeamCityServiceMessageWriter,
    flowNamespace: String,
    evaluationOrdinal: Long
  ) = this(writer, flowNamespace, evaluationOrdinal, null)

  private final class StartedGroup(val flowId: String)

  private val lifecycleLock = new AnyRef
  private val startedGroups = mutable.Map.empty[String, StartedGroup]
  private val quarantinedGroupEnds = mutable.Map.empty[String, Int]
  private val nextInvocationOrdinalByName = mutable.Map.empty[String, Long]

  override def startGroup(name: String): Unit = lifecycleLock.synchronized {
    val startedGroup = new StartedGroup(nextGroupFlowId(name))
    writer.write(TestSuiteStarted(name, startedGroup.flowId))

    quarantinedGroupEnds.get(name) match {
      case Some(pendingEnds) =>
        quarantinedGroupEnds.update(name, pendingEnds + 1)
        val diagnostic = overlappingGroupDiagnostic(name)
        writer.write(BuildLogMessage(BuildLogStatus.Warning, diagnostic, Some(startedGroup.flowId)))
        writer.write(unsupportedOverlapFinish(name, startedGroup, diagnostic))
      case None =>
        startedGroups.remove(name) match {
          case Some(previousGroup) =>
            val diagnostic = overlappingGroupDiagnostic(name)
            writer.write(BuildLogMessage(BuildLogStatus.Warning, diagnostic, Some(startedGroup.flowId)))
            writer.write(unsupportedOverlapFinish(name, previousGroup, diagnostic))
            writer.write(unsupportedOverlapFinish(name, startedGroup, diagnostic))
            quarantinedGroupEnds.put(name, 2)
          case None =>
            startedGroups.put(name, startedGroup)
        }
    }
  }

  override def testEvent(event: TestEvent): Unit = lifecycleLock.synchronized {
    event.detail.foreach(reportSingleTest)
  }

  override def endGroup(name: String, throwable: Throwable): Unit = lifecycleLock.synchronized {
    finishGroup(name) { groupFlowId =>
      TestSuiteFinished(name, groupFlowId, Some(TestSuiteFailure(throwable.getMessage, formattedStackTrace(throwable))))
    }
  }

  override def endGroup(name: String, result: TestResult): Unit = lifecycleLock.synchronized {
    finishGroup(name)(groupFlowId => TestSuiteFinished(name, groupFlowId))
  }

  private def reportSingleTest(event: _root_.sbt.testing.Event): Unit = {
    val testName = qualifiedTestName(event)
    val eventFlowId = unambiguousStartedGroupFlowFor(testName).getOrElse {
      val orphanFlowId = stableFlowId(s"orphan-test:$testName")
      writer.write(BuildLogMessage(
        BuildLogStatus.Warning,
        s"Received SBT test callback for '$testName' without one unambiguous active test group; reporting it without suite ownership.",
        Some(orphanFlowId)
      ))
      orphanFlowId
    }
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

  private def finishGroup(name: String)(finishedMessage: String => TestSuiteFinished): Unit =
    quarantinedGroupEnds.get(name) match {
      case Some(pendingEnds) =>
        if (pendingEnds == 1) quarantinedGroupEnds.remove(name)
        else quarantinedGroupEnds.update(name, pendingEnds - 1)
        writer.write(BuildLogMessage(
          BuildLogStatus.Warning,
          s"Ignored SBT end callback for unsupported overlapping test group '$name'; its suite was already closed when the overlap was detected.",
          Some(stableFlowId(s"quarantined-group-end:$name"))
        ))
      case None =>
        startedGroups.remove(name) match {
          case Some(startedGroup) => writer.write(finishedMessage(startedGroup.flowId))
          case None =>
            writer.write(BuildLogMessage(
              BuildLogStatus.Warning,
              s"Received SBT end callback for test group '$name' without an active start; no suite finish was emitted.",
              Some(stableFlowId(s"orphan-group-end:$name"))
            ))
        }
    }

  private def unsupportedOverlapFinish(
    name: String,
    startedGroup: StartedGroup,
    diagnostic: String
  ): TestSuiteFinished =
    TestSuiteFinished(
      name,
      startedGroup.flowId,
      Some(TestSuiteFailure("Unsupported overlapping SBT test groups", diagnostic))
    )

  private def overlappingGroupDiagnostic(name: String): String =
    s"SBT reported overlapping test groups named '$name', but TestReportListener callbacks have no invocation identity. " +
      "The overlapping groups were closed as failed, and their remaining callbacks will be reported without suite ownership."

  /**
   * Returns the only active test group which can own a test event.
   *
   * Test frameworks are allowed to invoke [[testEvent]] from a worker other than the one that invoked
   * [[startGroup]]. A thread-derived flow therefore detached leaf events from their suite whenever executor
   * scheduling changed. A qualified test name belongs to its exact group or to the most-specific active group
   * which prefixes it. Selecting the longest prefix keeps nested framework suites associated with their inner
   * group rather than an outer one. A quarantined same-name overlap takes precedence over all prefix candidates:
   * the API cannot identify either owner, so the event is reported explicitly as an orphan.
   */
  private def unambiguousStartedGroupFlowFor(testName: String): Option[String] = {
    val quarantinedMatch = quarantinedGroupEnds.keysIterator.exists(groupName => ownsTest(groupName, testName))
    if (quarantinedMatch) None
    else
      startedGroups.iterator.collect {
        case (groupName, startedGroup) if ownsTest(groupName, testName) => groupName -> startedGroup.flowId
      }.toSeq.sortBy { case (groupName, _) => -groupName.length }.headOption.map(_._2)
  }

  private def ownsTest(groupName: String, testName: String): Boolean =
    testName == groupName || testName.startsWith(s"$groupName.")

  /**
   * Creates a distinct, deterministic, protocol-safe flow ID for one supported group invocation.
   *
   * The namespace isolates the project/configuration/task scope, the evaluation ordinal distinguishes fresh
   * listeners for that scope, and the per-name ordinal distinguishes repeated group names without coupling flows to
   * unrelated start order. The UUID avoids placing arbitrary SBT text directly into a TeamCity attribute. State
   * lookup, rather than a callback thread, preserves the flow through the finish.
   */
  private def nextGroupFlowId(name: String): String = {
    val nextInvocationOrdinal = nextInvocationOrdinalByName.getOrElse(name, 0L) + 1L
    nextInvocationOrdinalByName.update(name, nextInvocationOrdinal)
    stableFlowId(s"group:$nextInvocationOrdinal:$name")
  }

  private def stableFlowId(identity: String): String = {
    val flowIdentity = s"${flowNamespace.length}:$flowNamespace:$evaluationOrdinal:$identity"
    s"teamcity-sbt-test-${UUID.nameUUIDFromBytes(flowIdentity.getBytes(StandardCharsets.UTF_8))}"
  }
}

object SbtTestReportListener {
  private object PluginOwnerToken

  private val evaluationOrdinalsByNamespace = mutable.Map.empty[String, Long]

  private[logger] def pluginOwned(
    writer: TeamCityServiceMessageWriter,
    flowNamespace: String
  ): SbtTestReportListener =
    new SbtTestReportListener(writer, flowNamespace, nextEvaluationOrdinal(flowNamespace), PluginOwnerToken)

  private[logger] def isPluginOwned(listener: TestReportListener): Boolean = listener match {
    case teamCityListener: SbtTestReportListener => teamCityListener.ownerToken eq PluginOwnerToken
    case _ => false
  }

  /**
   * Distinguishes fresh evaluations of one scoped `testListeners` task without coupling other task namespaces.
   * SBT serializes repeated evaluations of one scoped task, so construction order is deterministic for a namespace.
   * The counter is process-local: reuse after an SBT JVM restart is safe because no flow from that process remains
   * open, while distinct concurrent project/configuration/task scopes have distinct namespaces.
   */
  private def nextEvaluationOrdinal(flowNamespace: String): Long = synchronized {
    val next = evaluationOrdinalsByNamespace.getOrElse(flowNamespace, 0L) + 1L
    evaluationOrdinalsByNamespace.update(flowNamespace, next)
    next
  }
}
