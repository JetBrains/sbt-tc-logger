package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtJacocoSemanticContractTest {
  import ObservedServiceMessageKind.*
  import ProcessResultContract.*
  import SemanticValuePattern.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private val Profiles = Vector("sbt-1-jdk8", "sbt-1-jdk17", "sbt-2-jdk17")
  private val FutureProfile = "sbt-3-jdk21"
  private val Suite = "fixture.jacoco.PointTest"
  private val TestName = s"$Suite.movesPoint"
  private val TaskSummary = "[success] Total time: 1.25 s, completed Aug 26, 2026, 12:00:00 PM"

  @Test def supportedCoordinatesSelectSemanticAndUnknownCoordinatesRemainExact(): Unit = {
    Profiles.foreach(profile => Assert.assertTrue(
      s"Expected semantic JaCoCo verification on $profile",
      SbtJacocoSemanticContracts.Report.forRuntimeProfile(profile).isInstanceOf[Semantic]
    ))
    Assert.assertEquals(ExactTranscript, SbtJacocoSemanticContracts.Report.forRuntimeProfile(FutureProfile))
  }

  @Test def everyProfileAcceptsExactCompilationInstrumentationTestAndCoverageShape(): Unit = Profiles.foreach { profile =>
    val contract = SbtJacocoSemanticContracts.semanticContractFor(profile)
    verify(transcript(profile), contract, exitCode = 0)
    Assert.assertEquals(4, contract.lifecycles.size)
    Assert.assertEquals(2, contract.optionalGroups.size)
    Assert.assertEquals(profile == "sbt-2-jdk17", contract.plainOutput.isInstanceOf[PlainOutputContract.Patterns])
  }

  @Test def coverageCountersPathsWarningsAndTestLifecycleRemainStrict(): Unit = Profiles.foreach { profile =>
    val contract = SbtJacocoSemanticContracts.semanticContractFor(profile)
    val valid = transcript(profile)
    val wrongCounter = updateUnique(valid, _.contains("Jacoco Coverage Report"), "coverage report",
      _.replace("77 of 113", "76 of 113"))
    val wrongInstrument = updateUnique(valid, _.contains("Instrumenting 2 classes"), "instrumentation",
      _.replace("Instrumenting 2 classes", "Instrumenting 1 class"))
    val testStart = uniqueIndex(valid, _.startsWith("##teamcity[testStarted"), "test start")
    val testFinish = uniqueIndex(valid, _.startsWith("##teamcity[testFinished"), "test finish")
    val reversed = valid.updated(testStart, valid(testFinish)).updated(testFinish, valid(testStart))
    val wrongFlow = updateUnique(valid, _.startsWith("##teamcity[testSuiteStarted"), "suite start",
      _.replace("flow-jacoco", "flow-other"))

    Vector(wrongCounter, wrongInstrument).foreach(lines =>
      assertViolation(collect(lines, contract, exitCode = 0), SemanticCardinalityFailure))
    assertViolation(collect(reversed, contract, exitCode = 0), OrderingFailure)
    assertViolation(collect(wrongFlow, contract, exitCode = 0), FlowOwnershipFailure)

    if (profile.startsWith("sbt-1")) {
      val wrongWarning = updateUnique(valid, _.contains("2 deprecations"), "deprecation warning",
        _.replace("2 deprecations", "3 deprecations"))
      assertViolation(collect(wrongWarning, contract, exitCode = 0), SemanticCardinalityFailure)
    } else {
      assertViolation(collect(valid.filterNot(_ == TaskSummary), contract, exitCode = 0), PlainOutputFailure)
    }
  }

  @Test def compositeJacocoFailureIsRenderedOnceWithViolationsAndBlockedChecks(): Unit = {
    val profile = "sbt-2-jdk17"
    val contract = SbtJacocoSemanticContracts.semanticContractFor(profile).copy(processResult = Success)
    val valid = transcript(profile)
    val wrongReport = updateUnique(valid, _.contains("Jacoco Coverage Report"), "coverage report",
      _.replace("77 of 113", "76 of 113"))
    val wrongFlow = updateUnique(wrongReport, _.startsWith("##teamcity[testSuiteStarted"), "suite start",
      _.replace("flow-jacoco", "flow-other"))
    val testStart = uniqueIndex(wrongFlow, _.startsWith("##teamcity[testStarted"), "test start")
    val testFinish = uniqueIndex(wrongFlow, _.startsWith("##teamcity[testFinished"), "test finish")
    val wrongOrder = wrongFlow.updated(testStart, wrongFlow(testFinish)).updated(testFinish, wrongFlow(testStart))
    val mutated = wrongOrder.filterNot(_ == TaskSummary) ++ Vector(
      "prefix ##teamcity[message text='malformed']",
      "##teamcity[fixtureUnexpected value='extra']"
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

  private def transcript(profile: String): Vector[String] = {
    val contract = SbtJacocoSemanticContracts.semanticContractFor(profile)
    val services = contract.events.map(event => renderEvent(event, profile))
    if (profile == "sbt-2-jdk17") services :+ TaskSummary else services
  }

  private def renderEvent(event: ExpectedSemanticEvent, profile: String): String = {
    def value(name: String): String = event.attributes.find(_._1 == name).map(_._2) match {
      case Some(Exact(expected)) => expected
      case Some(Embedded(prefix, key, suffix)) =>
        prefix + (if (key.toString.startsWith("build id:")) "100" else "/workspace") + suffix
      case Some(UnsignedDuration) => "17"
      case other => throw new AssertionError(s"Cannot render $name=$other for ${event.id}.")
    }
    val attributes = event.kind match {
      case CompilationStarted | CompilationFinished =>
        val flow = if (event.id.value.startsWith("main-")) "100:compile:compiler" else "100:test:compiler"
        Vector("compiler" -> value("compiler"), "flowId" -> flow)
      case BuildLogMessage =>
        Vector("status" -> value("status"), "flowId" -> value("flowId"), "text" -> value("text"))
      case InspectionType => Vector(
        "id" -> value("id"),
        "name" -> value("name"),
        "description" -> value("description"),
        "category" -> value("category")
      )
      case TestSuiteStarted | TestSuiteFinished =>
        Vector("name" -> value("name"), "flowId" -> "flow-jacoco")
      case TestStarted => Vector(
        "name" -> value("name"),
        "captureStandardOutput" -> value("captureStandardOutput"),
        "flowId" -> "flow-jacoco"
      )
      case TestFinished =>
        Vector("name" -> value("name"), "duration" -> value("duration"), "flowId" -> "flow-jacoco")
      case other => throw new AssertionError(s"Cannot render ${other.wireName} for ${event.id} on $profile.")
    }
    service(event.kind.wireName, attributes*)
  }

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

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
