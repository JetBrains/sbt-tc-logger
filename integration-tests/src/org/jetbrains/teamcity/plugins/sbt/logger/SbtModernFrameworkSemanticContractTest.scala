package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtModernFrameworkSemanticContractTest {
  import ProcessResultContract.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private enum Outcome {
    case Passing
    case Ignored
    case Failed(details: String, diagnostic: String, userFrame: String)
  }

  private final case class TestFixture(id: String, name: String, outcome: Outcome, duration: String = "8")

  private final case class FrameworkFixture(
    scenarioId: String,
    moduleName: String,
    suiteName: String,
    selection: SbtOutputVerificationSelection,
    tests: Vector[TestFixture]
  ) {
    def failed: TestFixture = tests.collectFirst { case test @ TestFixture(_, _, _: Outcome.Failed, _) => test }
      .getOrElse(throw new AssertionError(s"$scenarioId has no failed test fixture."))
    def ignored: TestFixture = tests.find(_.outcome == Outcome.Ignored)
      .getOrElse(throw new AssertionError(s"$scenarioId has no ignored test fixture."))
    def passing: TestFixture = tests.find(_.outcome == Outcome.Passing)
      .getOrElse(throw new AssertionError(s"$scenarioId has no passing test fixture."))
  }

  private val Profiles = Vector("sbt-1-jdk17", "sbt-2-jdk17")
  private val FutureProfile = "sbt-3-jdk21"
  private val TaskSummary = "[error] Total time: 1 s"
  private val TestFlow = "flow-modern-framework"
  private val CompilerFlow = "100:test:compiler"

  private val ScalaTestDiagnostic =
    "org.scalatest.exceptions.TestFailedException: intentional ScalaTest failure"
  private val ScalaTestUserFrame =
    "\tat ModernScalaTestSuite.testFun$proxy2$1(ModernScalaTestSuite.scala:9)"
  private val ScalaTestSecondUserFrame =
    "\tat ModernScalaTestSuite.$init$$$anonfun$2(ModernScalaTestSuite.scala:8)"
  private val ScalaTestDetails = ScalaTestDiagnostic +
    "\n\tat org.scalatest.Assertions.failImpl(Assertions.scala:1060)" +
    s"\n$ScalaTestUserFrame" +
    s"\n$ScalaTestSecondUserFrame" +
    "\n\tat org.scalatest.Suite.run(Suite.scala:100)"

  private val JUnit4Diagnostic =
    "java.lang.AssertionError: intentional JUnit 4 failure expected:<2> but was:<1>"
  private val JUnit4UserFrame = "\tat ModernJUnit4Suite.failingTest(ModernJUnit4Suite.scala:7)"
  private val JUnit4Details = JUnit4Diagnostic +
    "\n\tat org.junit.Assert.fail(Assert.java:89)" +
    s"\n$JUnit4UserFrame" +
    "\n\tat org.junit.runners.ParentRunner.run(ParentRunner.java:413)"

  private val JupiterDiagnostic =
    "org.opentest4j.AssertionFailedError: intentional Jupiter failure ==> expected: <2> but was: <1>"
  private val JupiterUserFrame = "\tat ModernJupiterSuite.failingTest(ModernJupiterSuite.scala:7)"
  private val JupiterDetails = JupiterDiagnostic +
    "\n\tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:590)" +
    s"\n$JupiterUserFrame"

  private val MUnitDiagnostic = "munit.ComparisonFailException: src/test/scala/ModernMUnitSuite.scala:7"
  private val MUnitUserFrame = "\tat ModernMUnitSuite.$init$$$anonfun$2(ModernMUnitSuite.scala:7)"
  private val MUnitDetails = MUnitDiagnostic +
    "\n6:  test(\"failing test\") {" +
    "\n7:    assertEquals(1, 2)" +
    "\n8:  }" +
    "\nvalues are not the same" +
    "\n=> Obtained" +
    "\n1" +
    "\n=> Diff (- expected, + obtained)" +
    "\n-2" +
    "\n+1" +
    "\n\tat munit.FunSuite.assertEquals(FunSuite.scala:12)" +
    s"\n$MUnitUserFrame\n"

  private val Fixtures = Vector(
    FrameworkFixture(
      "modern-scalatest",
      "modern-scalatest-reporting",
      "ModernScalaTestSuite",
      SbtModernFrameworkSemanticContracts.ScalaTest,
      Vector(
        TestFixture("passing", "ModernScalaTestSuite.passing test", Outcome.Passing),
        TestFixture("failing", "ModernScalaTestSuite.failing test",
          Outcome.Failed(ScalaTestDetails, ScalaTestDiagnostic, ScalaTestUserFrame)),
        TestFixture("ignored", "ModernScalaTestSuite.ignored test", Outcome.Ignored, duration = "-1")
      )
    ),
    FrameworkFixture(
      "modern-junit4",
      "modern-junit4-reporting",
      "ModernJUnit4Suite",
      SbtModernFrameworkSemanticContracts.JUnit4,
      Vector(
        TestFixture("failing", "ModernJUnit4Suite.failingTest",
          Outcome.Failed(JUnit4Details, JUnit4Diagnostic, JUnit4UserFrame)),
        TestFixture("ignored", "ModernJUnit4Suite.ignoredTest", Outcome.Ignored),
        TestFixture("passing", "ModernJUnit4Suite.passingTest", Outcome.Passing)
      )
    ),
    FrameworkFixture(
      "modern-jupiter",
      "modern-jupiter-reporting",
      "ModernJupiterSuite",
      SbtModernFrameworkSemanticContracts.Jupiter,
      Vector(
        TestFixture("failing", "ModernJupiterSuite.failingTest()",
          Outcome.Failed(JupiterDetails, JupiterDiagnostic, JupiterUserFrame)),
        TestFixture("ignored", "ModernJupiterSuite.ignoredTest()", Outcome.Ignored),
        TestFixture("passing", "ModernJupiterSuite.passingTest()", Outcome.Passing)
      )
    ),
    FrameworkFixture(
      "modern-munit",
      "modern-munit-reporting",
      "ModernMUnitSuite",
      SbtModernFrameworkSemanticContracts.MUnit,
      Vector(
        TestFixture("passing", "ModernMUnitSuite.passing test", Outcome.Passing),
        TestFixture("failing", "ModernMUnitSuite.failing test",
          Outcome.Failed(MUnitDetails, MUnitDiagnostic, MUnitUserFrame)),
        TestFixture("ignored", "ModernMUnitSuite.ignored test", Outcome.Ignored)
      )
    )
  )

  @Test def supportedCoordinatesSelectSemanticAndUnknownCoordinatesRemainExact(): Unit = {
    val checks = Fixtures.flatMap { fixture =>
      Profiles.map(profile =>
        fixture.selection.forRuntimeProfile(profile).isInstanceOf[Semantic] ->
          s"${fixture.scenarioId}/$profile did not select semantic verification") :+
        ((fixture.selection.forRuntimeProfile(FutureProfile) == ExactTranscript) ->
          s"${fixture.scenarioId}/$FutureProfile did not fall back to exact verification")
    }
    assertAll("modern-framework selection", checks)
  }

  @Test def allEightCoordinatesAcceptTheirProfileSpecificShape(): Unit = {
    val failures = Vector.newBuilder[String]
    Fixtures.foreach { fixture =>
      Profiles.foreach { profile =>
        captureFailure(failures, s"${fixture.scenarioId}/$profile") {
          verify(transcript(fixture, profile), fixture.selection.forRuntimeProfile(profile))
        }
      }
    }
    captureFailure(failures, "ScalaTest compiler bridge") {
      val fixture = fixtureFor("modern-scalatest")
      verify(transcript(fixture, "sbt-1-jdk17", includeBridge = true),
        fixture.selection.forRuntimeProfile("sbt-1-jdk17"))
    }
    captureFailure(failures, "Jupiter compiler bridge") {
      val fixture = fixtureFor("modern-jupiter")
      verify(transcript(fixture, "sbt-2-jdk17", includeBridge = true),
        fixture.selection.forRuntimeProfile("sbt-2-jdk17"))
    }
    assertNoFailures("modern-framework coordinate shapes", failures.result())
  }

  @Test def exactNamesDiagnosticsUserFramesAndOutcomesAreAllReported(): Unit = {
    val checks = Fixtures.flatMap { fixture =>
      val profile = "sbt-2-jdk17"
      val mode = fixture.selection.forRuntimeProfile(profile)
      val valid = transcript(fixture, profile)
      val failed = fixture.failed
      val Outcome.Failed(_, diagnostic, userFrame) = failed.outcome: @unchecked
      val wrongName = updateUnique(
        valid,
        line => line.startsWith("##teamcity[testStarted") && line.contains(s"name='${failed.name}'"),
        s"${fixture.scenarioId} failed-test start",
        _.replace(failed.name, s"${failed.name}.changed")
      )
      val wrongDiagnostic = updateUnique(
        valid,
        line => line.startsWith("##teamcity[testFailed") && line.contains(s"name='${failed.name}'"),
        s"${fixture.scenarioId} failure diagnostic",
        _.replace(diagnostic, s"$diagnostic changed")
      )
      val wrongUserFrame = updateUnique(
        valid,
        line => line.startsWith("##teamcity[testFailed") && line.contains(s"name='${failed.name}'"),
        s"${fixture.scenarioId} user frame",
        _.replace(userFrame, userFrame.replace(":7)", ":70)").replace(":9)", ":90)"))
      )
      val missingIgnored = valid.filterNot(line =>
        line.startsWith("##teamcity[testIgnored") && line.contains(s"name='${fixture.ignored.name}'"))

      Vector(
        hasViolation(collect(wrongName, mode), SemanticCardinalityFailure) ->
          s"${fixture.scenarioId} accepted a changed test name",
        hasViolation(collect(wrongDiagnostic, mode), SemanticCardinalityFailure) ->
          s"${fixture.scenarioId} accepted a changed failure diagnostic",
        hasViolation(collect(wrongUserFrame, mode), SemanticCardinalityFailure) ->
          s"${fixture.scenarioId} accepted a changed user frame",
        hasViolation(collect(missingIgnored, mode), SemanticCardinalityFailure) ->
          s"${fixture.scenarioId} accepted a missing ignored outcome"
      )
    }
    assertAll("modern-framework exact fixture semantics", checks)
  }

  @Test def recognizedFrameworkFramesAreStructuralButForeignFramesRemainInvalid(): Unit = {
    val structuralCases = Vector(
      (fixtureFor("modern-scalatest"), "Assertions.scala:1060", "Assertions.scala:9999", "org.scalatest", "com.foreign"),
      (fixtureFor("modern-junit4"), "Assert.java:89", "Assert.java:9999", "org.junit", "com.foreign"),
      (fixtureFor("modern-jupiter"), "Assertions.java:590", "Assertions.java:9999", "org.junit", "com.foreign")
    )
    val failures = Vector.newBuilder[String]
    val checks = Vector.newBuilder[(Boolean, String)]
    structuralCases.foreach { case (fixture, oldLocation, newLocation, frameworkOwner, foreignOwner) =>
      val mode = fixture.selection.forRuntimeProfile("sbt-1-jdk17")
      val valid = transcript(fixture, "sbt-1-jdk17")
      val changedLocation = updateUnique(valid, _.startsWith("##teamcity[testFailed"),
        s"${fixture.scenarioId} failure", _.replace(oldLocation, newLocation))
      captureFailure(failures, s"${fixture.scenarioId} structural source location") {
        verify(changedLocation, mode)
      }
      val foreignFrame = updateUnique(valid, _.startsWith("##teamcity[testFailed"),
        s"${fixture.scenarioId} failure", _.replace(frameworkOwner, foreignOwner))
      checks += hasViolation(collect(foreignFrame, mode), SemanticCardinalityFailure) ->
        s"${fixture.scenarioId} accepted a foreign framework frame"
    }

    val munit = fixtureFor("modern-munit")
    val munitMode = munit.selection.forRuntimeProfile("sbt-1-jdk17")
    val changedMUnitFrame = updateUnique(transcript(munit, "sbt-1-jdk17"),
      _.startsWith("##teamcity[testFailed"), "MUnit failure",
      _.replace("FunSuite.scala:12", "FunSuite.scala:9999"))
    checks += hasViolation(collect(changedMUnitFrame, munitMode), SemanticCardinalityFailure) ->
      "MUnit accepted a changed exact diagnostic frame"

    assertNoFailures("recognized framework structure", failures.result() ++
      checks.result().collect { case (false, message) => message })
  }

  @Test def lifecycleOwnershipOrderAndPlainOutputStayStrict(): Unit = {
    val fixture = fixtureFor("modern-junit4")
    val mode = fixture.selection.forRuntimeProfile("sbt-2-jdk17")
    val valid = transcript(fixture, "sbt-2-jdk17")
    val wrongOwnership = updateUnique(valid, _.startsWith("##teamcity[testSuiteStarted"), "suite start",
      _.replace(TestFlow, "flow-other"))
    val passingStart = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testStarted") && line.contains(s"name='${fixture.passing.name}'"),
      "passing-test start")
    val passingFinish = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testFinished") && line.contains(s"name='${fixture.passing.name}'"),
      "passing-test finish")
    val wrongOrder = valid.updated(passingStart, valid(passingFinish)).updated(passingFinish, valid(passingStart))
    val withBridge = transcript(fixture, "sbt-2-jdk17", includeBridge = true)
    val incompleteBridge = withBridge.filterNot(_.contains("Compilation completed in"))
    val summaryBeforeServices = valid.last +: valid.dropRight(1)

    assertAll("modern-framework structure", Vector(
      hasViolation(collect(wrongOwnership, mode), FlowOwnershipFailure) -> "suite ownership mismatch was accepted",
      hasViolation(collect(wrongOrder, mode), OrderingFailure) -> "reversed test lifecycle was accepted",
      hasViolation(collect(incompleteBridge, mode), SemanticCardinalityFailure) ->
        "half-present compiler bridge was accepted",
      hasViolation(collect(summaryBeforeServices, mode), PlainOutputFailure) ->
        "task summary before service messages was accepted"
    ))
  }

  @Test def compoundMutationReportsSixCategoriesAndBlockedConsequencesInOneFailure(): Unit = {
    val fixture = fixtureFor("modern-scalatest")
    val mode = fixture.selection.forRuntimeProfile("sbt-2-jdk17")
    val valid = transcript(fixture, "sbt-2-jdk17")
    val passingStart = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testStarted") && line.contains(s"name='${fixture.passing.name}'"),
      "passing-test start")
    val passingFinish = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testFinished") && line.contains(s"name='${fixture.passing.name}'"),
      "passing-test finish")
    val wrongOrder = valid.updated(passingStart, valid(passingFinish)).updated(passingFinish, valid(passingStart))
    val wrongOwnership = updateUnique(wrongOrder, _.startsWith("##teamcity[testSuiteStarted"), "suite start",
      _.replace(TestFlow, "flow-other"))
    val wrongLifecycle = updateUnique(wrongOwnership, _.startsWith("##teamcity[testSuiteFinished"), "suite finish",
      _.replace(fixture.suiteName, s"${fixture.suiteName}Changed"))
    val mutated = wrongLifecycle ++ Vector(
      "##teamcity[fixtureUnexpected value='extra']",
      "unexpected plain output"
    )
    val contract = effectiveContract(mode).copy(processResult = Success)
    val failure = expectFailure(SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 7))
    val categories = Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    )
    val checks = categories.flatMap(category => Vector(
      hasViolation(failure.failures, category) -> s"missing $category Violation",
      failure.getMessage.contains(s"[$category]") -> s"aggregate message omits $category"
    )) ++ Vector(
      failure.failures.exists(_.disposition == Blocked) -> "aggregate findings contain no Blocked consequence",
      failure.getMessage.contains("[Violation]") -> "aggregate message omits Violation dispositions",
      failure.getMessage.contains("[Blocked]") -> "aggregate message omits Blocked dispositions",
      !failure.failures.exists(_.category == MatcherComplexityFailure) ->
        "compound fixture exceeded the production matcher budget"
    )
    assertAll(s"aggregate verifier failure:\n${failure.getMessage}", checks)
  }

  private def transcript(
    fixture: FrameworkFixture,
    profile: String,
    includeBridge: Boolean = false
  ): Vector[String] = {
    val compiler = s"Scala compiler in Test [${fixture.scenarioId}]"
    val suffix = if (profile == "sbt-2-jdk17")
      s"/target/out/jvm/scala-3.8.4/${fixture.moduleName}/test-classes ..."
    else "/target/scala-3.8.4/test-classes ..."
    val bridge = if (includeBridge) Vector(
      message(CompilerFlow, "NORMAL",
        "[info] Non-compiled module 'compiler-bridge_3' for Scala 3.8.4. Compiling..."),
      message(CompilerFlow, "NORMAL", "[info]   Compilation completed in 1.25s.")
    ) else Vector.empty

    Vector(
      compilationBoundary("compilationStarted", compiler),
      message(CompilerFlow, "NORMAL", s"[info] compiling 1 Scala source to /work$suffix")
    ) ++ bridge ++ Vector(
      message(CompilerFlow, "NORMAL", "[info] done compiling"),
      compilationBoundary("compilationFinished", compiler),
      service("testSuiteStarted", "name" -> fixture.suiteName, "flowId" -> TestFlow)
    ) ++ fixture.tests.flatMap(testLines) ++ Vector(
      service("testSuiteFinished", "name" -> fixture.suiteName, "flowId" -> TestFlow),
      TaskSummary
    )
  }

  private def testLines(test: TestFixture): Vector[String] = {
    val start = service("testStarted", "name" -> test.name,
      "captureStandardOutput" -> "true", "flowId" -> TestFlow)
    val outcome = test.outcome match {
      case Outcome.Passing => Vector.empty
      case Outcome.Ignored => Vector(service("testIgnored", "name" -> test.name, "flowId" -> TestFlow))
      case Outcome.Failed(details, _, _) =>
        Vector(service("testFailed", "name" -> test.name, "details" -> details, "flowId" -> TestFlow))
    }
    val finish = service("testFinished", "name" -> test.name,
      "duration" -> test.duration, "flowId" -> TestFlow)
    Vector(start) ++ outcome ++ Vector(finish)
  }

  private def compilationBoundary(kind: String, compiler: String): String =
    service(kind, "compiler" -> compiler, "flowId" -> CompilerFlow)

  private def message(flow: String, status: String, text: String): String =
    service("message", "status" -> status, "flowId" -> flow, "text" -> text)

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

  private def fixtureFor(scenarioId: String): FrameworkFixture =
    Fixtures.find(_.scenarioId == scenarioId)
      .getOrElse(throw new AssertionError(s"Unknown modern-framework fixture '$scenarioId'."))

  private def effectiveContract(mode: SbtOutputVerification): SbtSemanticContract = mode match {
    case Semantic(contract) => contract
    case other => throw new AssertionError(s"Expected semantic verification, got $other")
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

  private def hasViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Boolean = findings.exists(finding =>
    finding.category == category && finding.disposition == Violation)

  private def expectFailure(body: => Unit): SbtSemanticVerificationException = try {
    body
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def captureFailure(failures: scala.collection.mutable.Builder[String, Vector[String]], label: String)
                            (body: => Unit): Unit = try body catch {
    case failure: Throwable => failures += s"$label: ${failure.getMessage}"
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

  private def uniqueIndex(lines: Vector[String], predicate: String => Boolean, description: String): Int = {
    val indexes = lines.indices.filter(index => predicate(lines(index))).toVector
    require(indexes.size == 1, s"Expected one $description, found indexes $indexes.")
    indexes.head
  }

  private def assertAll(context: String, checks: Seq[(Boolean, String)]): Unit =
    assertNoFailures(context, checks.collect { case (false, message) => message }.toVector)

  private def assertNoFailures(context: String, failures: Vector[String]): Unit =
    Assert.assertTrue(
      s"$context\n${failures.map(message => s" - $message").mkString("\n")}",
      failures.isEmpty
    )

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
