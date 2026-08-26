package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtJUnitTaskSemanticContractTest {
  import PlainOutputContract.*
  import ProcessResultContract.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private val AllProfiles = Vector("sbt-1.4-jdk8", "sbt-1-jdk8", "sbt-1-jdk17", "sbt-2-jdk17")
  private val IntegrationProfiles = Vector("sbt-1-jdk8", "sbt-1-jdk17", "sbt-2-jdk17")
  private val FutureProfile = "sbt-3-jdk21"
  private val TaskSummary = "[error] elapsed time: 1 s, cache 20%, 2 disk cache hits"

  private val MainSuite = "thisis.a.test.ATest"
  private val MainFailure = "comparing unequal ints is WRONG expected:<3> but was:<1>"
  private val MainFrame = "\tat thisis.a.test.ATest.testMeToo(ATest.scala:15)"
  private val IntegrationSuite = "thisis.a.test.IntegrationATest"
  private val IntegrationFailure = "integration test failure expected:<3> but was:<1>"
  private val IntegrationFrame = "\tat thisis.a.test.IntegrationATest.testFails(IntegrationATest.scala:11)"

  private val CommonSelections = Vector(
    "junit-pass-and-failure" -> SbtJUnitTaskSemanticContracts.PassAndFailure,
    "junit-test-quick" -> SbtJUnitTaskSemanticContracts.TestQuick,
    "junit-test-only" -> SbtJUnitTaskSemanticContracts.TestOnly
  )

  @Test def knownCoordinatesSelectSemanticAndUnsupportedCoordinatesRemainExact(): Unit = {
    CommonSelections.foreach { case (scenarioId, selection) =>
      AllProfiles.foreach(profile => assertSemantic(scenarioId, profile, selection))
      Assert.assertEquals(ExactTranscript, selection.forRuntimeProfile(FutureProfile))
    }

    assertSemantic("junit-test-full", "sbt-2-jdk17", SbtJUnitTaskSemanticContracts.TestFull)
    Vector("sbt-1.4-jdk8", "sbt-1-jdk8", "sbt-1-jdk17", FutureProfile).foreach(profile =>
      Assert.assertEquals(ExactTranscript, SbtJUnitTaskSemanticContracts.TestFull.forRuntimeProfile(profile)))

    IntegrationProfiles.foreach(profile =>
      assertSemantic("integration-test-quick", profile, SbtJUnitTaskSemanticContracts.IntegrationTestQuick))
    Vector("sbt-1.4-jdk8", FutureProfile).foreach(profile => Assert.assertEquals(
      ExactTranscript,
      SbtJUnitTaskSemanticContracts.IntegrationTestQuick.forRuntimeProfile(profile)
    ))
  }

  @Test def everySupportedCoordinateAcceptsItsTaskSpecificShape(): Unit = {
    CommonSelections.foreach { case (scenarioId, selection) =>
      AllProfiles.foreach(profile => verify(transcript(scenarioId, profile), selection.forRuntimeProfile(profile)))
    }
    verify(transcript("junit-test-full", "sbt-2-jdk17"),
      SbtJUnitTaskSemanticContracts.TestFull.forRuntimeProfile("sbt-2-jdk17"))
    IntegrationProfiles.foreach(profile => verify(
      transcript("integration-test-quick", profile),
      SbtJUnitTaskSemanticContracts.IntegrationTestQuick.forRuntimeProfile(profile)
    ))

    assertShape("junit-test-quick", "sbt-1-jdk8", events = 11)
    assertShape("junit-test-quick", "sbt-1-jdk17", events = 15)
    assertShape("junit-test-quick", "sbt-2-jdk17", events = 9)
    assertShape("junit-test-full", "sbt-2-jdk17", events = 9)
    assertShape("integration-test-quick", "sbt-1-jdk17", events = 15)
  }

  @Test def taskSelectionKeepsOutputAndTerminalTasksExact(): Unit = {
    val coordinates = Vector(
      "junit-pass-and-failure" -> "sbt-2-jdk17",
      "junit-test-only" -> "sbt-1-jdk17",
      "junit-test-only" -> "sbt-2-jdk17",
      "junit-test-full" -> "sbt-2-jdk17",
      "integration-test-quick" -> "sbt-1-jdk17",
      "integration-test-quick" -> "sbt-2-jdk17"
    )
    coordinates.foreach { case (scenarioId, profile) =>
      val mode = selectionFor(scenarioId).forRuntimeProfile(profile)
      val spec = transcriptSpec(scenarioId, profile)
      val valid = transcript(scenarioId, profile)
      val wrongOutputFlow = updateUnique(
        valid,
        line => line.startsWith("##teamcity[message") && line.contains(" failed: "),
        s"$scenarioId task failure summary",
        _.replace(
          s":${spec.flowConfiguration}:general:${spec.outputTask}",
          s":${spec.flowConfiguration}:general:wrongTask"
        )
      )
      val wrongTerminal = updateUnique(
        valid,
        _.contains("sbt.TestsFailedException: Tests unsuccessful"),
        s"$scenarioId terminal task failure",
        _.replace(s" / ${spec.terminalTask})", " / wrongTask)")
      )
      val invalidDuration = updateUnique(
        valid,
        line => line.startsWith("##teamcity[message") && line.contains(" failed: "),
        s"$scenarioId task failure summary",
        _.replace("took 0.25 sec", "took immediately sec")
      )

      val wrongOutputFindings = collect(wrongOutputFlow, mode)
      assertViolation(wrongOutputFindings, SemanticCardinalityFailure)
      assertBlockedCategory(wrongOutputFindings, FlowOwnershipFailure)
      Vector(wrongTerminal, invalidDuration).foreach(lines =>
        assertViolation(collect(lines, mode), SemanticCardinalityFailure))
    }
  }

  @Test def userFailureDataStaysExactWhileRecognizedJUnitTailsRemainStructural(): Unit = {
    val scenarioId = "junit-test-quick"
    val profile = "sbt-1-jdk17"
    val mode = SbtJUnitTaskSemanticContracts.TestQuick.forRuntimeProfile(profile)
    val valid = transcript(scenarioId, profile)
    verify(updateUnique(
      valid,
      _.startsWith("##teamcity[testFailed"),
      "JUnit failed test",
      _.replace("Assert.java:88", "Assert.java:999")
    ), mode)

    val wrongMessage = updateUnique(valid, _.startsWith("##teamcity[testFailed"), "JUnit failed test",
      _.replace(MainFailure, "changed assertion"))
    val wrongUserFrame = updateUnique(valid, _.startsWith("##teamcity[testFailed"), "JUnit failed test",
      _.replace("ATest.scala:15", "ATest.scala:16"))
    val foreignTail = updateUnique(valid, _.startsWith("##teamcity[testFailed"), "JUnit failed test",
      _.replace("org.junit.Assert.fail", "com.foreign.Assert.fail"))

    Vector(wrongMessage, wrongUserFrame, foreignTail).foreach(lines =>
      assertViolation(collect(lines, mode), SemanticCardinalityFailure))
  }

  @Test def jdk17TaskStackFramesUseFiniteRolesInsteadOfLiteralSourceLines(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = SbtJUnitTaskSemanticContracts.TestQuick.forRuntimeProfile(profile)
    val valid = transcript("junit-test-quick", profile)
    val changedSourceLines = valid.map(line =>
      if (line.contains("AccessorImpl.java:") || line.contains("Method.java:"))
        line.replace(":77)", ":177)").replace(":43)", ":143)").replace(":569)", ":1569)")
      else line)
    verify(changedSourceLines, mode)

    val foreignRole = updateUnique(
      valid,
      _.contains("NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:"),
      "native reflection frame",
      _.replace("NativeMethodAccessorImpl.invoke", "ForeignAccessor.invoke")
    )
    val missingRole = valid.filterNot(_.contains("DelegatingMethodAccessorImpl.invoke"))
    Vector(foreignRole, missingRole).foreach(lines =>
      assertViolation(collect(lines, mode), SemanticCardinalityFailure))
  }

  @Test def suiteAndTestsKeepOwnershipLifecycleAndFixtureOrder(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = SbtJUnitTaskSemanticContracts.TestQuick.forRuntimeProfile(profile)
    val valid = transcript("junit-test-quick", profile)
    val wrongOwnership = updateUnique(valid, _.startsWith("##teamcity[testSuiteStarted"), "suite start",
      _.replace("flow-junit", "flow-other"))
    val passStart = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testStarted") && line.contains(".testMe'"), "passing test start")
    val passFinish = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testFinished") && line.contains(".testMe'"), "passing test finish")
    val wrongOrder = valid.updated(passStart, valid(passFinish)).updated(passFinish, valid(passStart))
    val terminal = uniqueIndex(valid, _.contains("sbt.TestsFailedException"), "terminal task failure")
    val terminalBeforeSuite = valid(terminal) +: valid.patch(terminal, Vector.empty, 1)

    assertViolation(collect(wrongOwnership, mode), FlowOwnershipFailure)
    Vector(wrongOrder, terminalBeforeSuite).foreach(lines =>
      assertViolation(collect(lines, mode), OrderingFailure))
  }

  @Test def compoundJUnitMutationReportsTheWholeMismatchInOneAssertion(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = SbtJUnitTaskSemanticContracts.TestQuick.forRuntimeProfile(profile)
    val valid = transcript("junit-test-quick", profile)
    val passStart = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testStarted") && line.contains(".testMe'"), "passing test start")
    val passFinish = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testFinished") && line.contains(".testMe'"), "passing test finish")
    val wrongOrder = valid.updated(passStart, valid(passFinish)).updated(passFinish, valid(passStart))
    val wrongOwnership = updateUnique(wrongOrder, _.startsWith("##teamcity[testSuiteStarted"), "suite start",
      _.replace("flow-junit", "flow-other"))
    val wrongLifecycle = updateUnique(wrongOwnership, _.startsWith("##teamcity[testSuiteFinished"), "suite finish",
      _.replace(MainSuite, "thisis.a.test.BrokenTest"))
    val wrongFailure = updateUnique(wrongLifecycle, _.startsWith("##teamcity[testFailed"), "JUnit failed test",
      _.replace(MainFailure, "changed assertion"))
    val mutated = wrongFailure ++ Vector(
      "##teamcity[fixtureUnexpected value='extra']",
      "unexpected plain output"
    )
    val contract = effectiveContract(mode).copy(processResult = Success)
    val failure = expectFailure(SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 7))
    val findings = failure.failures

    Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach { category =>
      assertViolation(findings, category)
      Assert.assertTrue(failure.getMessage, failure.getMessage.contains(category.toString))
    }
    assertBlocked(findings, OrderingFailure, "edge:suite-finish->task-terminal-failure")
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Violation]"))
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Blocked]"))
    Assert.assertFalse(describe(findings), findings.exists(_.category == MatcherComplexityFailure))
  }

  private final case class TranscriptSpec(
    suite: String,
    failedTest: String,
    passingTest: String,
    failureMessage: String,
    userFrame: String,
    flowConfiguration: String,
    displayConfiguration: String,
    outputTask: String,
    terminalTask: String
  )

  private def transcriptSpec(scenarioId: String, profile: String): TranscriptSpec = {
    val sbt2 = isSbt2(profile)
    scenarioId match {
      case "junit-pass-and-failure" => mainSpec(
        outputTask = if (sbt2) "testQuick" else "test",
        terminalTask = if (sbt2) "testQuick" else "test"
      )
      case "junit-test-quick" => mainSpec("testQuick", "testQuick")
      case "junit-test-only" => mainSpec(
        outputTask = if (sbt2) "testSelected" else "testOnly",
        terminalTask = if (sbt2) "testSelected" else "testOnly"
      )
      case "junit-test-full" => mainSpec("test", "testFull")
      case "integration-test-quick" => TranscriptSpec(
        IntegrationSuite,
        s"$IntegrationSuite.testFails",
        s"$IntegrationSuite.testPasses",
        IntegrationFailure,
        IntegrationFrame,
        "it",
        "IntegrationTest",
        "testQuick",
        "testQuick"
      )
    }
  }

  private def mainSpec(outputTask: String, terminalTask: String): TranscriptSpec = TranscriptSpec(
    MainSuite,
    s"$MainSuite.testMeToo",
    s"$MainSuite.testMe",
    MainFailure,
    MainFrame,
    "test",
    "Test",
    outputTask,
    terminalTask
  )

  private def transcript(scenarioId: String, profile: String): Vector[String] = {
    val spec = transcriptSpec(scenarioId, profile)
    val outputFlow = s"100:${spec.flowConfiguration}:general:${spec.outputTask}"
    val terminalFlow = s"100:${spec.flowConfiguration}:general:${spec.terminalTask}"
    val renderedFailure =
      if (isSbt2(profile)) spec.failureMessage
      else s"java.lang.AssertionError: ${spec.failureMessage}"
    val taskStack = if (isSbt2(profile)) Vector.empty else Vector(
      message(outputFlow, "ERROR", s"[error]     at ${spec.userFrame.stripPrefix("\tat ")}")
    ) ++ (if (profile == "sbt-1-jdk17") Vector(
      message(outputFlow, "ERROR",
        "[error]     at jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)"),
      message(outputFlow, "ERROR",
        "[error]     at jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)"),
      message(outputFlow, "ERROR",
        "[error]     at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)"),
      message(outputFlow, "ERROR", "[error]     at java.lang.reflect.Method.invoke(Method.java:569)")
    ) else Vector.empty) ++ Vector(message(outputFlow, "ERROR", "[error]     ..."))

    Vector(
      service("testSuiteStarted", "name" -> spec.suite, "flowId" -> "flow-junit"),
      message(outputFlow, "ERROR",
        s"[error] Test ${spec.failedTest} failed: $renderedFailure, took 0.25 sec")
    ) ++ taskStack ++ Vector(
      service("testStarted", "name" -> spec.failedTest,
        "captureStandardOutput" -> "true", "flowId" -> "flow-junit"),
      service("testFailed", "name" -> spec.failedTest,
        "details" -> failureDetails(spec), "flowId" -> "flow-junit"),
      service("testFinished", "name" -> spec.failedTest,
        "duration" -> "17.5", "flowId" -> "flow-junit"),
      service("testStarted", "name" -> spec.passingTest,
        "captureStandardOutput" -> "true", "flowId" -> "flow-junit"),
      service("testFinished", "name" -> spec.passingTest,
        "duration" -> "8", "flowId" -> "flow-junit"),
      service("testSuiteFinished", "name" -> spec.suite, "flowId" -> "flow-junit"),
      message(terminalFlow, "ERROR",
        s"[error] (${spec.displayConfiguration} / ${spec.terminalTask}) " +
          "sbt.TestsFailedException: Tests unsuccessful")
    ) ++ Option.when(isSbt2(profile))(TaskSummary)
  }

  private def failureDetails(spec: TranscriptSpec): String =
    s"java.lang.AssertionError: ${spec.failureMessage}" +
      "\n\tat org.junit.Assert.fail(Assert.java:88)" +
      s"\n${spec.userFrame}" +
      "\n\tat com.novocode.junit.JUnitRunner.run(JUnitRunner.java:1)"

  private def message(flow: String, status: String, text: String): String =
    service("message", "status" -> status, "flowId" -> flow, "text" -> text)

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

  private def selectionFor(scenarioId: String): SbtOutputVerificationSelection = scenarioId match {
    case "junit-pass-and-failure" => SbtJUnitTaskSemanticContracts.PassAndFailure
    case "junit-test-quick" => SbtJUnitTaskSemanticContracts.TestQuick
    case "junit-test-only" => SbtJUnitTaskSemanticContracts.TestOnly
    case "junit-test-full" => SbtJUnitTaskSemanticContracts.TestFull
    case "integration-test-quick" => SbtJUnitTaskSemanticContracts.IntegrationTestQuick
  }

  private def assertSemantic(
    scenarioId: String,
    profile: String,
    selection: SbtOutputVerificationSelection
  ): Unit = Assert.assertTrue(
    s"Expected semantic $scenarioId on $profile",
    selection.forRuntimeProfile(profile).isInstanceOf[Semantic]
  )

  private def assertShape(scenarioId: String, profile: String, events: Int): Unit = {
    val contract = SbtJUnitTaskSemanticContracts.semanticContractFor(scenarioId, profile)
    Assert.assertEquals(s"Events for $scenarioId/$profile", events, contract.events.map(_.multiplicity).sum)
    Assert.assertEquals(s"Lifecycles for $scenarioId/$profile", 3, contract.lifecycles.size)
    Assert.assertEquals(DelegatedToHarness, contract.processResult)
    Assert.assertEquals(isSbt2(profile), contract.plainOutput.isInstanceOf[Patterns])
  }

  private def effectiveContract(mode: SbtOutputVerification): SbtSemanticContract = mode match {
    case Semantic(contract) => contract
    case other => throw new AssertionError(s"Expected semantic mode, got $other")
  }

  private def verify(lines: Vector[String], mode: SbtOutputVerification): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      effectiveContract(mode),
      exitCode = 1,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(
    lines: Vector[String],
    mode: SbtOutputVerification
  ): Vector[SbtSemanticFailure] = SbtSemanticOutputVerifier.collect(
    lines,
    effectiveContract(mode),
    exitCode = 1,
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

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category Violation in ${describe(findings)}",
    findings.exists(finding => finding.category == category && finding.disposition == Violation)
  )

  private def assertBlocked(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory,
    identity: String
  ): Unit = Assert.assertTrue(
    s"Expected $category Blocked for $identity in ${describe(findings)}",
    findings.exists(finding =>
      finding.category == category && finding.disposition == Blocked && finding.semanticIdentity == identity)
  )

  private def assertBlockedCategory(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category Blocked in ${describe(findings)}",
    findings.exists(finding => finding.category == category && finding.disposition == Blocked)
  )

  private def expectFailure(body: => Unit): SbtSemanticVerificationException = try {
    body
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def describe(findings: Vector[SbtSemanticFailure]): String =
    findings.map(finding => (finding.category, finding.disposition, finding.semanticIdentity)).mkString(", ")

  private def isSbt2(profile: String): Boolean = profile == "sbt-2-jdk17"

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
