// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

import java.util.concurrent.CountDownLatch
import scala.collection.mutable

import org.jetbrains.teamcity.plugins.sbt.logger.SbtApiAdapter
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.*
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.{TeamCityServiceMessage, TeamCityServiceMessageWriter}
import org.junit.Assert.{assertEquals, assertFalse, assertNotEquals, assertTrue}
import org.junit.Test
import sbt.TestEvent
import sbt.protocol.testing.TestResult
import sbt.testing.{Event, Fingerprint, OptionalThrowable, Status, TestSelector}

class SbtTestReportListenerTest {
  @Test
  def bindsAListenerAtEverySupportedConcreteTestTask(): Unit = {
    assertEquals(
      SbtApiAdapter.testTaskKeys,
      SbtApiAdapter.testReportListenerTasks.map(_.taskKey).toSet
    )
  }

  @Test
  def routesWorkerThreadEventsToTheirMostSpecificStartedGroup(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "worker-move")

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

  @Test
  def keepsIndependentProjectAndConfigurationInvocationsIsolated(): Unit = {
    val writer = new CapturingWriter
    val firstListener = new SbtTestReportListener(writer, "project-a:Test")
    val secondListener = new SbtTestReportListener(writer, "project-b:Test")

    firstListener.startGroup("example.SharedSuite")
    secondListener.startGroup("example.SharedSuite")
    firstListener.testEvent(TestEvent(Seq(success("example.SharedSuite.first"))))
    secondListener.testEvent(TestEvent(Seq(success("example.SharedSuite.second"))))
    firstListener.endGroup("example.SharedSuite", TestResult.Passed)
    secondListener.endGroup("example.SharedSuite", TestResult.Passed)

    val messages = writer.messages.toVector
    val suiteStarts = messages.collect { case message: TestSuiteStarted => message }
    val suiteFinishes = messages.collect { case message: TestSuiteFinished => message }
    val firstTestFlow = messages.collectFirst { case TestStarted("example.SharedSuite.first", flow, _) => flow }.get
    val secondTestFlow = messages.collectFirst { case TestStarted("example.SharedSuite.second", flow, _) => flow }.get

    assertEquals(2, suiteStarts.size)
    assertEquals(2, suiteFinishes.size)
    assertNotEquals("Independent scoped listeners must produce distinct flows", firstTestFlow, secondTestFlow)
    assertEquals(firstTestFlow, suiteStarts.head.flowId)
    assertEquals(secondTestFlow, suiteStarts.last.flowId)
    assertEquals(Set(firstTestFlow, secondTestFlow), suiteFinishes.map(_.flowId).toSet)
  }

  @Test
  def givesSequentialSameNameGroupsDistinctBalancedLifecycles(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "repeated-task")

    listener.startGroup("example.RepeatedSuite")
    listener.endGroup("example.RepeatedSuite", TestResult.Passed)
    listener.startGroup("example.RepeatedSuite")
    listener.endGroup("example.RepeatedSuite", TestResult.Passed)

    val starts = writer.messages.collect { case message: TestSuiteStarted => message }
    val finishes = writer.messages.collect { case message: TestSuiteFinished => message }

    assertEquals(2, starts.size)
    assertEquals(2, finishes.size)
    assertNotEquals("Separate suite invocations must not reuse a flow", starts.head.flowId, starts.last.flowId)
    assertEquals(starts.map(_.flowId), finishes.map(_.flowId))
  }

  @Test
  def unrelatedGroupStartOrderDoesNotChangeTheirFlows(): Unit = {
    val firstWriter = new CapturingWriter
    val secondWriter = new CapturingWriter
    val firstListener = new SbtTestReportListener(firstWriter, "order-independent", 17L)
    val secondListener = new SbtTestReportListener(secondWriter, "order-independent", 17L)

    firstListener.startGroup("example.AlphaSuite")
    firstListener.startGroup("example.BetaSuite")
    secondListener.startGroup("example.BetaSuite")
    secondListener.startGroup("example.AlphaSuite")

    val firstFlows = firstWriter.messages.collect { case TestSuiteStarted(name, flow) => name -> flow }.toMap
    val secondFlows = secondWriter.messages.collect { case TestSuiteStarted(name, flow) => name -> flow }.toMap

    assertEquals(firstFlows, secondFlows)
  }

  @Test
  def freshListenersWithTheSameNamespaceDoNotCollide(): Unit = {
    val firstWriter = new CapturingWriter
    val secondWriter = new CapturingWriter
    val firstListener = new SbtTestReportListener(firstWriter, "same-scoped-task")
    val secondListener = new SbtTestReportListener(secondWriter, "same-scoped-task")

    firstListener.startGroup("example.SharedSuite")
    secondListener.startGroup("example.SharedSuite")

    val firstFlow = firstWriter.messages.collectFirst { case TestSuiteStarted(_, flow) => flow }.get
    val secondFlow = secondWriter.messages.collectFirst { case TestSuiteStarted(_, flow) => flow }.get
    assertNotEquals("Fresh task evaluations must not reopen the same flow", firstFlow, secondFlow)
  }

  @Test
  def failsAndBalancesOverlappingSameNameGroupsInsteadOfGuessingOwnership(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "unsupported-overlap")

    listener.startGroup("example.DuplicateSuite")
    listener.startGroup("example.DuplicateSuite")
    listener.testEvent(TestEvent(Seq(success("example.DuplicateSuite.test"))))
    listener.endGroup("example.DuplicateSuite", TestResult.Passed)
    listener.endGroup("example.DuplicateSuite", TestResult.Passed)

    val messages = writer.messages.toVector
    val starts = messages.collect { case message: TestSuiteStarted => message }
    val finishes = messages.collect { case message: TestSuiteFinished => message }
    val orphanTestFlow = messages.collectFirst { case TestStarted("example.DuplicateSuite.test", flow, _) => flow }.get

    assertEquals(2, starts.size)
    assertEquals(2, finishes.size)
    assertEquals(starts.map(_.flowId).toSet, finishes.map(_.flowId).toSet)
    assertTrue(finishes.forall(_.failure.exists(_.message == "Unsupported overlapping SBT test groups")))
    assertFalse("An ambiguous test callback must not be assigned to either suite", starts.exists(_.flowId == orphanTestFlow))
    assertTrue(messages.exists {
      case BuildLogMessage(BuildLogStatus.Warning, text, _) => text.contains("callbacks have no invocation identity")
      case _ => false
    })
    assertEquals(2, messages.count {
      case BuildLogMessage(BuildLogStatus.Warning, text, _) => text.contains("already closed when the overlap was detected")
      case _ => false
    })
  }

  @Test
  def balancesAndQuarantinesThreeOverlappingSameNameGroups(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "three-way-overlap")

    listener.startGroup("example.TripleSuite")
    listener.startGroup("example.TripleSuite")
    listener.startGroup("example.TripleSuite")
    listener.endGroup("example.TripleSuite", TestResult.Passed)
    listener.endGroup("example.TripleSuite", TestResult.Passed)
    listener.endGroup("example.TripleSuite", TestResult.Passed)

    val starts = writer.messages.collect { case message: TestSuiteStarted => message }
    val finishes = writer.messages.collect { case message: TestSuiteFinished => message }

    assertEquals(3, starts.size)
    assertEquals(3, starts.map(_.flowId).distinct.size)
    assertEquals(starts.map(_.flowId).toSet, finishes.map(_.flowId).toSet)
    assertTrue(finishes.forall(_.failure.exists(_.message == "Unsupported overlapping SBT test groups")))
    assertEquals(3, writer.messages.count {
      case BuildLogMessage(BuildLogStatus.Warning, text, _) => text.contains("already closed when the overlap was detected")
      case _ => false
    })
  }

  @Test
  def acceptsTheSameGroupAgainAfterOverlapQuarantineDrains(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "overlap-recovery")

    listener.startGroup("example.RecoveringSuite")
    listener.startGroup("example.RecoveringSuite")
    listener.endGroup("example.RecoveringSuite", TestResult.Passed)
    listener.endGroup("example.RecoveringSuite", TestResult.Passed)
    val recoveryStartIndex = writer.messages.size

    listener.startGroup("example.RecoveringSuite")
    listener.testEvent(TestEvent(Seq(success("example.RecoveringSuite.test"))))
    listener.endGroup("example.RecoveringSuite", TestResult.Passed)

    val recoveryMessages = writer.messages.drop(recoveryStartIndex).toVector
    val recoveryFlow = recoveryMessages.collectFirst { case TestSuiteStarted(_, flow) => flow }.get
    assertEquals(
      Vector(
        TestSuiteStarted("example.RecoveringSuite", recoveryFlow),
        TestStarted("example.RecoveringSuite.test", recoveryFlow),
        TestFinished("example.RecoveringSuite.test", 0L, recoveryFlow),
        TestSuiteFinished("example.RecoveringSuite", recoveryFlow)
      ),
      recoveryMessages
    )
  }

  @Test
  def sameNameQuarantineDoesNotAffectOtherGroups(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "unaffected-group")

    listener.startGroup("example.DuplicateSuite")
    listener.startGroup("example.DuplicateSuite")
    val unaffectedStartIndex = writer.messages.size
    listener.startGroup("example.OtherSuite")
    listener.testEvent(TestEvent(Seq(success("example.OtherSuite.test"))))
    listener.endGroup("example.OtherSuite", TestResult.Passed)

    val unaffectedMessages = writer.messages.drop(unaffectedStartIndex).toVector
    val unaffectedFlow = unaffectedMessages.collectFirst { case TestSuiteStarted(_, flow) => flow }.get
    assertEquals(
      Vector(
        TestSuiteStarted("example.OtherSuite", unaffectedFlow),
        TestStarted("example.OtherSuite.test", unaffectedFlow),
        TestFinished("example.OtherSuite.test", 0L, unaffectedFlow),
        TestSuiteFinished("example.OtherSuite", unaffectedFlow)
      ),
      unaffectedMessages
    )
  }

  @Test
  def marksOnlyFactoryCreatedListenersAsPluginOwned(): Unit = {
    val writer = new CapturingWriter
    val legacyUserListener = new SbtTestReportListener(writer)
    val namespacedUserListener = new SbtTestReportListener(writer, "user-listener")
    val pluginListener = SbtTestReportListener.pluginOwned(writer, "plugin-listener")
    val configuredListeners = Vector(legacyUserListener, pluginListener, namespacedUserListener)

    assertFalse(SbtTestReportListener.isPluginOwned(legacyUserListener))
    assertFalse(SbtTestReportListener.isPluginOwned(namespacedUserListener))
    assertTrue(SbtTestReportListener.isPluginOwned(pluginListener))
    assertEquals(
      Vector(legacyUserListener, namespacedUserListener),
      configuredListeners.filterNot(SbtTestReportListener.isPluginOwned)
    )
  }

  @Test
  def reportsOrphanTestCallbacksOnAStableNonThreadFlow(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "orphan-test")

    val firstThread = new Thread(() => listener.testEvent(TestEvent(Seq(success("example.OrphanSuite.test")))))
    val secondThread = new Thread(() => listener.testEvent(TestEvent(Seq(success("example.OrphanSuite.test")))))
    firstThread.start()
    firstThread.join()
    secondThread.start()
    secondThread.join()

    val messages = writer.messages.toVector
    val testFlows = messages.collect { case TestStarted("example.OrphanSuite.test", flow, _) => flow }
    val finishFlows = messages.collect { case TestFinished("example.OrphanSuite.test", _, flow) => flow }

    assertEquals(Vector(testFlows.head, testFlows.head), testFlows)
    assertEquals(testFlows, finishFlows)
    assertEquals(2, messages.count {
      case BuildLogMessage(BuildLogStatus.Warning, text, Some(flow)) =>
        text.contains("without one unambiguous active test group") && flow == testFlows.head
      case _ => false
    })
  }

  @Test
  def diagnosesOrphanGroupEndsWithoutEmittingUnbalancedFinishes(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "orphan-end")

    listener.endGroup("example.NeverStarted", TestResult.Passed)
    listener.endGroup("example.AlsoNeverStarted", new IllegalStateException("boom"))

    assertFalse(writer.messages.exists(_.isInstanceOf[TestSuiteFinished]))
    assertEquals(2, writer.messages.count {
      case BuildLogMessage(BuildLogStatus.Warning, text, Some(_)) => text.contains("without an active start")
      case _ => false
    })
  }

  @Test
  def balancesConcurrentGroupEndsWithTheirOriginalFlows(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "concurrent-ends")
    listener.startGroup("example.FirstSuite")
    listener.startGroup("example.SecondSuite")
    val ready = new CountDownLatch(2)
    val release = new CountDownLatch(1)

    def endingThread(name: String): Thread = new Thread(() => {
      ready.countDown()
      release.await()
      listener.endGroup(name, TestResult.Passed)
    })

    val firstThread = endingThread("example.FirstSuite")
    val secondThread = endingThread("example.SecondSuite")
    firstThread.start()
    secondThread.start()
    ready.await()
    release.countDown()
    firstThread.join()
    secondThread.join()

    val messages = writer.messages.toVector
    val startsByName = messages.collect { case TestSuiteStarted(name, flow) => name -> flow }.toMap
    val finishesByName = messages.collect { case TestSuiteFinished(name, flow, _) => name -> flow }.toMap

    assertEquals(startsByName, finishesByName)
    assertEquals(2, finishesByName.size)
  }

  @Test
  def removesAnsiStylesFromFailureDetails(): Unit = {
    val writer = new CapturingWriter
    val listener = new SbtTestReportListener(writer, "ansi")
    listener.startGroup("example.AnsiSuite")

    listener.testEvent(TestEvent(Seq(failure("example.AnsiSuite.fails", new AssertionError("\u001b[1mbold\u001b[0m")))))

    val details = writer.messages.collectFirst { case TestFailed("example.AnsiSuite.fails", value, _) => value }.get
    assertTrue(details.contains("bold"))
    assertFalse(details.contains("\u001b["))
  }

  private def success(name: String): Event = new Event {
    override def fullyQualifiedName(): String = name
    override def fingerprint(): Fingerprint = null
    override def selector(): TestSelector = new TestSelector(name)
    override def status(): Status = Status.Success
    override def throwable(): OptionalThrowable = new OptionalThrowable
    override def duration(): Long = 0L
  }

  private def failure(name: String, error: Throwable): Event = new Event {
    override def fullyQualifiedName(): String = name
    override def fingerprint(): Fingerprint = null
    override def selector(): TestSelector = new TestSelector(name)
    override def status(): Status = Status.Failure
    override def throwable(): OptionalThrowable = new OptionalThrowable(error)
    override def duration(): Long = 0L
  }

  private final class CapturingWriter extends TeamCityServiceMessageWriter {
    private val buffer = mutable.ArrayBuffer.empty[TeamCityServiceMessage]

    def messages: Seq[TeamCityServiceMessage] = buffer.synchronized(buffer.toVector)

    override def write(message: TeamCityServiceMessage): Unit = buffer.synchronized(buffer += message)
  }
}
