package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtScalaTestParallelEventsSemanticContractTest {
  import ObservedServiceMessageKind.*
  import PlainOutputContract.*
  import PlainOutputPattern.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private final case class TestFixture(id: String, name: String, failed: Boolean)
  private final case class SuiteFixture(id: String, name: String, flow: String, tests: Vector[TestFixture])

  private val RuntimeProfiles = Vector("sbt-1.4-jdk8", "sbt-1-jdk8", "sbt-1-jdk17", "sbt-2-jdk17")
  private val FailurePrefix = "org.scalatest.exceptions.TestFailedException: Test failed"
  private val UserFrame = "\tat tests.NonParallelTest.$anonfun$new$2(NonParallelTest.scala:12)"
  private val ParallelGeneratedTail =
    "\n\tat tests.ParallelTest.org$scalatest$ParallelTestExecution$$super$runTest(ParallelTest.scala:5)" +
      "\n\tat tests.ParallelTest.runTest(ParallelTest.scala:5)"
  private val ParallelGeneratedFrameMarker =
    "tests.ParallelTest.org$scalatest$ParallelTestExecution$$super$runTest"
  private val TaskSummary = "[error] Total time: 1 s"

  private val Suites = Vector(
    nestedSuite("suite-non-parallel", "suites.NonParallelSuite", "flow-suite-non-parallel"),
    nestedSuite("suite-parallel", "suites.ParallelSuite", "flow-suite-parallel"),
    directSuite("direct-non-parallel", "tests.NonParallelTest", "flow-direct-non-parallel"),
    directSuite("direct-parallel", "tests.ParallelTest", "flow-direct-parallel")
  )

  @Test def profilesDeclareExactProtocolSetsAndAcceptIndependentInterleavings(): Unit = {
    RuntimeProfiles.foreach { profile =>
      val mode = modeFor(profile)
      Assert.assertTrue(s"Expected semantic mode for $profile", mode.isInstanceOf[Semantic])
      val contract = effectiveContract(mode)

      Assert.assertEquals(4, contract.events.count(_.kind == TestSuiteStarted))
      Assert.assertEquals(4, contract.events.count(_.kind == TestSuiteFinished))
      Assert.assertEquals(12, contract.events.count(_.kind == TestStarted))
      Assert.assertEquals(12, contract.events.count(_.kind == TestFinished))
      Assert.assertEquals(6, contract.events.count(_.kind == TestFailed))
      Assert.assertEquals(17, contract.lifecycles.size)
      Assert.assertEquals(6, contract.distinctBindings.size)
      Assert.assertEquals(ProcessResultContract.DelegatedToHarness, contract.processResult)
      Assert.assertEquals(5, contract.happensBefore.size)
      Assert.assertTrue(contract.happensBefore.forall(_.before.value.startsWith("compile-")))
      val expectedPlain =
        if (isSbt2(profile)) Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
        else RejectAll
      Assert.assertEquals(expectedPlain, contract.plainOutput)

      val failureLines = serialTranscript(profile).filter(_.startsWith("##teamcity[testFailed"))
      val parallelFailures = failureLines.filter(_.contains(ParallelGeneratedFrameMarker))
      val nonParallelFailures = failureLines.filterNot(_.contains(ParallelGeneratedFrameMarker))
      Assert.assertEquals(6, failureLines.size)
      Assert.assertEquals(3, parallelFailures.size)
      Assert.assertEquals(3, nonParallelFailures.size)
      Assert.assertTrue(parallelFailures.forall(_.contains("tests.ParallelTest.should")))
      Assert.assertTrue(nonParallelFailures.forall(_.contains("tests.NonParallelTest.should")))

      verify(serialTranscript(profile), mode)
      verify(serialTranscript(profile, Suites.reverse, reverseTests = true), mode)
      verify(interleavedTranscript(profile), mode)
    }
  }

  @Test def exactNamesOwnershipAndFailureDetailsRejectMutations(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = modeFor(profile)
    val valid = serialTranscript(profile)
    verify(valid, mode)
    val wrongName = updateUnique(valid,
      line => line.contains("testStarted name='tests.NonParallelTest.should Write Passing Tests'"),
      "direct non-parallel passing test start",
      _.replace("Write Passing Tests", "Write Renamed Tests"))
    assertViolation(collect(wrongName, mode), SemanticCardinalityFailure)

    val wrongOwnership = updateUnique(valid,
      line => line.contains("testSuiteStarted name='suites.NonParallelSuite'") &&
        line.contains("flowId='flow-suite-non-parallel'"),
      "non-parallel suite start",
      _.replace("flow-suite-non-parallel", "flow-suite-parallel"))
    assertViolation(collect(wrongOwnership, mode), FlowOwnershipFailure)

    val failureLine: String => Boolean = _.contains(
      "testFailed name='tests.NonParallelTest.should Write Failing Tests'")
    val wrongPrefix = updateUnique(valid, failureLine, "direct non-parallel failure",
      _.replace("TestFailedException: Test failed", "TestFailedException: changed"))
    val wrongUserFrame = updateUnique(valid, failureLine, "direct non-parallel failure",
      _.replace("NonParallelTest.scala:12", "NonParallelTest.scala:13"))
    val foreignTail = updateUnique(valid, failureLine, "direct non-parallel failure",
      _.replace(
        "NonParallelTest.scala:12)",
        "NonParallelTest.scala:12)|n\tat com.foreign.Runner.run(Runner.scala:1)"
      ))
    val parallelGeneratedTail = updateUnique(valid, failureLine, "direct non-parallel failure",
      _.replace(
        "NonParallelTest.scala:12)",
        "NonParallelTest.scala:12)" + teamCityEscape(ParallelGeneratedTail)
      ))
    Vector(wrongPrefix, wrongUserFrame, foreignTail, parallelGeneratedTail).foreach(output =>
      assertViolation(collect(output, mode), SemanticCardinalityFailure))
  }

  @Test def childAndSuiteLifecyclesRejectLocalReversalsAndMissingBoundaries(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = modeFor(profile)
    val valid = serialTranscript(profile)
    val childStart = uniqueIndex(valid,
      _.contains("testStarted name='tests.ParallelTest.should Write Passing Tests'"),
      "direct parallel passing test start")
    val childFinish = uniqueIndex(valid,
      _.contains("testFinished name='tests.ParallelTest.should Write Passing Tests'"),
      "direct parallel passing test finish")
    val reversedChild = valid
      .updated(childStart, valid(childFinish))
      .updated(childFinish, valid(childStart))
    assertViolation(
      collect(reversedChild, mode),
      OrderingFailure,
      "edge:direct-parallel-pass-start->direct-parallel-pass-finish"
    )

    val wrongSuiteFinish = updateUnique(valid,
      _.contains("testSuiteFinished name='tests.NonParallelTest'"),
      "direct non-parallel suite finish",
      _.replace("tests.NonParallelTest", "tests.BrokenNonParallelTest"))
    val findings = collect(wrongSuiteFinish, mode)
    assertViolation(findings, SemanticCardinalityFailure)
    assertViolation(findings, LifecycleFailure)
  }

  @Test def compilationBridgeProfilePathAndPlainOutputRulesRemainStrict(): Unit = {
    RuntimeProfiles.foreach(profile => verify(serialTranscript(profile, includeBridge = true), modeFor(profile)))

    val profile = "sbt-1-jdk17"
    val mode = modeFor(profile)
    val bridged = serialTranscript(profile, includeBridge = true)
    val completion = uniqueIndex(bridged,
      line => line.contains("flowId='100:test:compiler'") && line.contains("Compilation completed in"),
      "compiler-bridge completion")
    val incomplete = bridged.patch(completion, Vector.empty, 1)
    assertViolation(collect(incomplete, mode), SemanticCardinalityFailure)

    val wrongPath = updateUnique(serialTranscript(profile), _.contains("compiling 4 Scala sources"),
      "SBT 1 compilation info", _.replace("/target/scala-2.13/", "/target/scala-3/"))
    val wrongDone = updateUnique(serialTranscript(profile), _.contains("done compiling"),
      "compilation done", _.replace("done compiling", "finished compiling"))
    Vector(wrongPath, wrongDone).foreach(output =>
      assertViolation(collect(output, mode), SemanticCardinalityFailure))

    assertViolation(collect(serialTranscript(profile) :+ TaskSummary, mode), PlainOutputFailure)
    val sbt2 = serialTranscript("sbt-2-jdk17")
    assertViolation(collect(sbt2.last +: sbt2.dropRight(1), modeFor("sbt-2-jdk17")), PlainOutputFailure)
  }

  @Test def oneFinalAssertionReportsSixIndependentViolationCategoriesWithinProductionBudget(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = modeFor(profile)
    val valid = serialTranscript(profile)

    val orderStart = uniqueIndex(valid,
      _.contains("testStarted name='tests.ParallelTest.should Write Passing Tests'"),
      "direct parallel passing test start")
    val orderFinish = uniqueIndex(valid,
      _.contains("testFinished name='tests.ParallelTest.should Write Passing Tests'"),
      "direct parallel passing test finish")
    val wrongOrder = valid
      .updated(orderStart, valid(orderFinish))
      .updated(orderFinish, valid(orderStart))
    val wrongOwnership = updateUnique(wrongOrder,
      line => line.contains("testSuiteStarted name='suites.NonParallelSuite'") &&
        line.contains("flowId='flow-suite-non-parallel'"),
      "non-parallel suite start",
      _.replace("flow-suite-non-parallel", "flow-suite-parallel"))
    val wrongLifecycle = updateUnique(wrongOwnership,
      _.contains("testSuiteFinished name='tests.NonParallelTest'"),
      "direct non-parallel suite finish",
      _.replace("tests.NonParallelTest", "tests.BrokenNonParallelTest"))
    val mutated = wrongLifecycle :+ "unexpected plain output"
    val contract = effectiveContract(mode).copy(processResult = ProcessResultContract.Success)
    val failure = expectSemanticFailure(SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 7))
    val findings = failure.failures

    val expectedCategories = Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    )
    expectedCategories.foreach { category =>
      assertViolation(findings, category)
      Assert.assertTrue(failure.getMessage.contains(s"[$category]"))
    }
    Assert.assertFalse(findings.exists(_.category == MatcherComplexityFailure))
  }

  private def nestedSuite(id: String, name: String, flow: String): SuiteFixture =
    SuiteFixture(id, name, flow, Vector(
      TestFixture(s"$id-non-parallel-pass", s"$name.tests.NonParallelTest.should Write Passing Tests", failed = false),
      TestFixture(s"$id-non-parallel-fail", s"$name.tests.NonParallelTest.should Write Failing Tests", failed = true),
      TestFixture(s"$id-parallel-pass", s"$name.tests.ParallelTest.should Write Passing Tests", failed = false),
      TestFixture(s"$id-parallel-fail", s"$name.tests.ParallelTest.should Write Failing Tests", failed = true)
    ))

  private def directSuite(id: String, name: String, flow: String): SuiteFixture =
    SuiteFixture(id, name, flow, Vector(
      TestFixture(s"$id-pass", s"$name.should Write Passing Tests", failed = false),
      TestFixture(s"$id-fail", s"$name.should Write Failing Tests", failed = true)
    ))

  private def serialTranscript(
    profile: String,
    suites: Vector[SuiteFixture] = Suites,
    reverseTests: Boolean = false,
    includeBridge: Boolean = false
  ): Vector[String] = withProfilePlain(
    profile,
    compilation(profile, includeBridge) ++ suites.flatMap(suiteLines(_, reverseTests))
  )

  private def interleavedTranscript(profile: String): Vector[String] = {
    val starts = Suites.map(suiteStart)
    val tests = Suites.reverse.flatMap(suite => suite.tests.reverse.flatMap(testLines(_, suite.flow)))
    val finishes = Suites.reverse.map(suiteFinish)
    withProfilePlain(profile, compilation(profile) ++ starts ++ tests ++ finishes)
  }

  private def compilation(profile: String, includeBridge: Boolean = false): Vector[String] = {
    val flow = "100:test:compiler"
    val suffix =
      if (isSbt2(profile)) "/target/out/jvm/scala-2.12.20/scalateamcitytestreporterbug/test-classes ..."
      else "/target/scala-2.13/test-classes ..."
    val bridge = Option.when(includeBridge)(Vector(
      message(flow, "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.18. Compiling..."),
      message(flow, "[info]   Compilation completed in 1.25s.")
    )).getOrElse(Vector.empty)
    Vector(
      compilationBoundary("compilationStarted", flow),
      message(flow, s"[info] compiling 4 Scala sources to /work$suffix")
    ) ++ bridge ++ Vector(
      message(flow, "[info] done compiling"),
      compilationBoundary("compilationFinished", flow)
    )
  }

  private def suiteLines(suite: SuiteFixture, reverseTests: Boolean): Vector[String] = {
    val tests = if (reverseTests) suite.tests.reverse else suite.tests
    Vector(suiteStart(suite)) ++ tests.flatMap(testLines(_, suite.flow)) ++ Vector(suiteFinish(suite))
  }

  private def suiteStart(suite: SuiteFixture): String =
    s"##teamcity[testSuiteStarted name='${teamCityEscape(suite.name)}' flowId='${suite.flow}']"

  private def suiteFinish(suite: SuiteFixture): String =
    s"##teamcity[testSuiteFinished name='${teamCityEscape(suite.name)}' flowId='${suite.flow}']"

  private def testLines(test: TestFixture, flow: String): Vector[String] = {
    val start =
      s"##teamcity[testStarted name='${teamCityEscape(test.name)}' captureStandardOutput='true' flowId='$flow']"
    val failure = Option.when(test.failed) {
      val generatedTail = Option.when(isParallelTest(test.name))(ParallelGeneratedTail).getOrElse("")
      val details = FailurePrefix +
        "\n\tat org.scalatest.Assertions.fail(Assertions.scala:933)\n" +
        UserFrame +
        "\n\tat org.scalatest.Suite.run(Suite.scala:1114)" +
        generatedTail
      s"##teamcity[testFailed name='${teamCityEscape(test.name)}' " +
        s"details='${teamCityEscape(details)}' flowId='$flow']"
    }.toVector
    val finish =
      s"##teamcity[testFinished name='${teamCityEscape(test.name)}' duration='17.5' flowId='$flow']"
    Vector(start) ++ failure ++ Vector(finish)
  }

  private def compilationBoundary(kind: String, flow: String): String =
    s"##teamcity[$kind compiler='Scala compiler in Test |[scalatest-parallel-events|]' flowId='$flow']"

  private def message(flow: String, value: String): String =
    s"##teamcity[message status='NORMAL' flowId='$flow' text='${teamCityEscape(value)}']"

  private def withProfilePlain(profile: String, serviceMessages: Vector[String]): Vector[String] =
    if (isSbt2(profile)) serviceMessages :+ TaskSummary else serviceMessages

  private def isSbt2(profile: String): Boolean = profile == "sbt-2-jdk17"

  private def isParallelTest(name: String): Boolean =
    name.startsWith("tests.ParallelTest.") || name.contains(".tests.ParallelTest.")

  private def modeFor(profile: String): SbtOutputVerification =
    SbtScalaTestParallelEventsSemanticContracts.Failure.forRuntimeProfile(profile)

  private def effectiveContract(mode: SbtOutputVerification): SbtSemanticContract = mode match {
    case Semantic(contract) => contract
    case other => throw new AssertionError(s"Parallel ScalaTest scenario must use semantic mode, got $other")
  }

  private def verify(lines: Vector[String], mode: SbtOutputVerification): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      effectiveContract(mode),
      exitCode = 1,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(lines: Vector[String], mode: SbtOutputVerification): Vector[SbtSemanticFailure] =
    SbtSemanticOutputVerifier.collect(
      lines,
      effectiveContract(mode),
      exitCode = 1,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def expectSemanticFailure(action: => Unit): SbtSemanticVerificationException = try {
    action
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def updateUnique(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String,
    update: String => String
  ): Vector[String] = {
    val index = uniqueIndex(lines, predicate, description)
    lines.updated(index, update(lines(index)))
  }

  private def uniqueIndex(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String
  ): Int = {
    val indexes = lines.indices.filter(index => predicate(lines(index))).toVector
    require(indexes.size == 1, s"Expected one $description, found indexes $indexes.")
    indexes.head
  }

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory,
    semanticIdentity: String = ""
  ): Unit = Assert.assertTrue(
    s"Expected $category Violation${Option.when(semanticIdentity.nonEmpty)(s" for $semanticIdentity").getOrElse("")} " +
      s"in ${findings.map(finding => (finding.category, finding.disposition, finding.semanticIdentity))}",
    findings.exists(finding =>
      finding.category == category &&
        finding.disposition == Violation &&
        (semanticIdentity.isEmpty || finding.semanticIdentity == semanticIdentity)
    )
  )

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
