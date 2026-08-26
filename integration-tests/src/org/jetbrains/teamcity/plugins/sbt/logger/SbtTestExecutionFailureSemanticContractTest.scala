package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtTestExecutionFailureSemanticContractTest {
  import ProcessResultContract.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private val Profiles = Vector("sbt-1-jdk17", "sbt-2-jdk17")
  private val FutureProfile = "sbt-3-jdk21"
  private val Suite = "FailedTest"
  private val TestName = s"$Suite.fails"
  private val FailureMessage = "#9 direct task-result fixture"
  private val TaskSummary = "[error] elapsed time: 1 s, cache 20%, 2 disk cache hits"

  @Test def supportedCoordinatesAreSemanticAndUnknownCoordinatesRemainExact(): Unit = {
    Vector(
      SbtTestExecutionFailureSemanticContracts.IncompleteResult,
      SbtTestExecutionFailureSemanticContracts.ForkedChildExit
    ).foreach { selection =>
      Profiles.foreach(profile => Assert.assertTrue(
        s"Expected semantic verification on $profile",
        selection.forRuntimeProfile(profile).isInstanceOf[Semantic]
      ))
      Assert.assertEquals(ExactTranscript, selection.forRuntimeProfile(FutureProfile))
    }
  }

  @Test def incompleteResultContractsAcceptBothRuntimeShapes(): Unit = Profiles.foreach { profile =>
    val contract = SbtTestExecutionFailureSemanticContracts.incompleteResultContractFor(profile)
    verify(incompleteTranscript(profile), contract, exitCode = 0)
    Assert.assertEquals(if (isSbt2(profile)) 6 else 12, contract.events.size)
    Assert.assertEquals(2, contract.lifecycles.size)
    Assert.assertEquals(PlainOutputContract.RejectAll, contract.plainOutput)
  }

  @Test def incompleteResultKeepsUserFailureTaskFlowAndLifecycleStrict(): Unit = {
    val profile = "sbt-1-jdk17"
    val contract = SbtTestExecutionFailureSemanticContracts.incompleteResultContractFor(profile)
    val valid = incompleteTranscript(profile)
    val wrongMessage = updateUnique(valid, _.startsWith("##teamcity[testFailed"), "failed test",
      _.replace(FailureMessage, "changed fixture failure"))
    val wrongTask = updateUnique(valid, _.contains(" failed: "), "task failure summary",
      _.replace(":test:general:test", ":test:general:testQuick"))
    val wrongFlow = updateUnique(valid, _.startsWith("##teamcity[testStarted"), "test start",
      _.replace("flow-test", "flow-other"))
    val start = uniqueIndex(valid, _.startsWith("##teamcity[testStarted"), "test start")
    val finish = uniqueIndex(valid, _.startsWith("##teamcity[testFinished"), "test finish")
    val reversed = valid.updated(start, valid(finish)).updated(finish, valid(start))

    Vector(wrongMessage, wrongTask).foreach(lines =>
      assertViolation(collect(lines, contract, exitCode = 0), SemanticCardinalityFailure))
    assertViolation(collect(wrongFlow, contract, exitCode = 0), FlowOwnershipFailure)
    assertViolation(collect(reversed, contract, exitCode = 0), OrderingFailure)
  }

  @Test def forkedChildExitContractsKeepRuntimeSpecificFailuresAndSummaryStrict(): Unit = {
    Profiles.foreach { profile =>
      val contract = SbtTestExecutionFailureSemanticContracts.forkedChildExitContractFor(profile)
      val valid = forkedTranscript(profile)
      verify(valid, contract, exitCode = 1)
      Assert.assertEquals(if (isSbt2(profile)) 2 else 1, contract.events.size)

      val wrongExit = if (isSbt2(profile))
        valid.map(_.replace("code 255", "code 1"))
      else
        valid.map(_.replace("Tests unsuccessful", "Changed failure"))
      assertViolation(collect(wrongExit, contract, exitCode = 1), SemanticCardinalityFailure)
      if (isSbt2(profile)) {
        val withoutSummary = valid.filterNot(_ == TaskSummary)
        assertViolation(collect(withoutSummary, contract, exitCode = 1), PlainOutputFailure)
        val reversed = Vector(valid(1), valid(0), valid(2))
        assertViolation(collect(reversed, contract, exitCode = 1), OrderingFailure)
      }
    }
  }

  @Test def oneFinalAssertionReportsEveryIndependentFailureAndBlockedCheck(): Unit = {
    val profile = "sbt-1-jdk17"
    val contract = SbtTestExecutionFailureSemanticContracts.incompleteResultContractFor(profile)
      .copy(processResult = Success)
    val valid = incompleteTranscript(profile)
    val start = uniqueIndex(valid, _.startsWith("##teamcity[testStarted"), "test start")
    val finish = uniqueIndex(valid, _.startsWith("##teamcity[testFinished"), "test finish")
    val wrongOrder = valid.updated(start, valid(finish)).updated(finish, valid(start))
    val wrongOwnership = updateUnique(
      wrongOrder,
      _.startsWith("##teamcity[testSuiteStarted"),
      "suite start",
      _.replace("flow-test", "flow-other")
    )
    val wrongFailure = updateUnique(
      wrongOwnership,
      _.startsWith("##teamcity[testFailed"),
      "failed test",
      _.replace(FailureMessage, "changed fixture failure")
    )
    val mutated = wrongFailure ++ Vector(
      "prefix ##teamcity[message text='malformed']",
      "##teamcity[fixtureUnexpected value='extra']",
      "unexpected plain output"
    )
    val failure = expectFailure(SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 7))
    val findings = failure.failures

    Vector(
      WireProtocolFailure,
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach { category =>
      assertViolation(findings, category)
      Assert.assertTrue(failure.getMessage, failure.getMessage.contains(s"[$category]"))
    }
    Assert.assertTrue(describe(findings), findings.exists(_.disposition == Blocked))
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Violation]"))
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Blocked]"))
    Assert.assertFalse(describe(findings), findings.exists(_.category == MatcherComplexityFailure))
  }

  private def incompleteTranscript(profile: String): Vector[String] = {
    val sbt2 = isSbt2(profile)
    val task = if (sbt2) "testQuick" else "test"
    val flow = s"100:test:general:$task"
    val taskFailure =
      s"[error] Test $TestName failed: ${if (sbt2) "" else "java.lang.AssertionError: "}" +
        s"$FailureMessage, took 0.25 sec"
    val sbt1Stack = if (sbt2) Vector.empty else Vector(
      message(flow, "[error]     at FailedTest.fails(FailedTest.scala:5)"),
      message(flow, "[error]     at jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)"),
      message(flow,
        "[error]     at jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)"),
      message(flow,
        "[error]     at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)"),
      message(flow, "[error]     at java.lang.reflect.Method.invoke(Method.java:569)"),
      message(flow, "[error]     ...")
    )

    Vector(
      service("testSuiteStarted", "name" -> Suite, "flowId" -> "flow-test"),
      message(flow, taskFailure)
    ) ++ sbt1Stack ++ Vector(
      service("testStarted", "name" -> TestName,
        "captureStandardOutput" -> "true", "flowId" -> "flow-test"),
      service("testFailed", "name" -> TestName,
        "details" -> failureDetails(profile), "flowId" -> "flow-test"),
      service("testFinished", "name" -> TestName, "duration" -> "17", "flowId" -> "flow-test"),
      service("testSuiteFinished", "name" -> Suite, "flowId" -> "flow-test")
    )
  }

  private def failureDetails(profile: String): String =
    s"java.lang.AssertionError: $FailureMessage" +
      s"\n\tat org.junit.Assert.fail(Assert.java:${if (isSbt2(profile)) 88 else 89})" +
      "\n\tat FailedTest.fails(FailedTest.scala:5)" +
      "\n\tat com.novocode.junit.JUnitRunner.run(JUnitRunner.java:1)"

  private def forkedTranscript(profile: String): Vector[String] = if (isSbt2(profile)) Vector(
    message("100:test:general:testQuick",
      "[error] java.lang.RuntimeException: Forked test process exited with code 255"),
    message("100:test:general:testQuick",
      "[error] (Test / testQuick) Forked test process exited with code 255"),
    TaskSummary
  ) else Vector(message(
    "100:test:general:test",
    "[error] (Test / test) sbt.TestsFailedException: Tests unsuccessful"
  ))

  private def message(flow: String, text: String): String =
    service("message", "status" -> "ERROR", "flowId" -> flow, "text" -> text)

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

  private def verify(lines: Vector[String], contract: SbtSemanticContract, exitCode: Int): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      contract,
      exitCode,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(
    lines: Vector[String],
    contract: SbtSemanticContract,
    exitCode: Int
  ): Vector[SbtSemanticFailure] = SbtSemanticOutputVerifier.collect(
    lines,
    contract,
    exitCode,
    delegated = SbtDelegatedVerification(processResultVerified = true)
  )

  private def updateUnique(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String,
    update: String => String
  ): Vector[String] = {
    val index = uniqueIndex(lines, predicate, description)
    lines.updated(index, update(lines(index)))
  }

  private def uniqueIndex(lines: Vector[String], predicate: String => Boolean, description: String): Int = {
    val indexes = lines.indices.filter(index => predicate(lines(index))).toVector
    require(indexes.size == 1, s"Expected one $description, found indexes $indexes.")
    indexes.head
  }

  private def expectFailure(action: => Unit): SbtSemanticVerificationException = try {
    action
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category Violation in ${describe(findings)}",
    findings.exists(finding => finding.category == category && finding.disposition == Violation)
  )

  private def describe(findings: Vector[SbtSemanticFailure]): String =
    findings.map(finding =>
      (finding.category, finding.disposition, finding.semanticIdentity)).mkString("\n")

  private def isSbt2(profile: String): Boolean = profile == "sbt-2-jdk17"

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
