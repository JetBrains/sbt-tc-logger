package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtSpecs2SemanticContractTest {
  import ProcessResultContract.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private val AllProfiles = Vector("sbt-1.4-jdk8", "sbt-1-jdk8", "sbt-1-jdk17", "sbt-2-jdk17")
  private val FutureProfile = "sbt-3-jdk21"
  private val TaskSummary = "[success] Total time: 1 s"
  private val ExampleSuite = "org.jetbrains.teamcity.ExampleSpec"
  private val ExampleNames = Vector(
    s"$ExampleSuite.The 'Hello world' string should::contain 11 characters",
    s"$ExampleSuite.The 'Hello world' string should::start with 'Hello'",
    s"$ExampleSuite.The 'Hello world' string should::end with 'world'"
  )
  private val IgnoredSuite = "HelloWorldSpec"
  private val IgnoredNames = Vector(
    s"$IgnoredSuite.The 'Hello world' string should::contain 11 characters",
    s"$IgnoredSuite.The 'Hello world' string should::start with 'Hello'",
    s"$IgnoredSuite.The 'Hello world' string should::end with 'world'"
  )

  @Test def supportedCoordinatesSelectSemanticAndUnknownCoordinatesRemainExact(): Unit = {
    Vector(
      SbtSpecs2SemanticContracts.IgnoredTests,
      SbtSpecs2SemanticContracts.TestOnlyExamples
    ).foreach { selection =>
      AllProfiles.foreach(profile =>
        Assert.assertTrue(selection.forRuntimeProfile(profile).isInstanceOf[Semantic]))
      Assert.assertEquals(ExactTranscript, selection.forRuntimeProfile(FutureProfile))
    }
    Assert.assertTrue(
      SbtSpecs2SemanticContracts.Descriptions.forRuntimeProfile("sbt-2-jdk17").isInstanceOf[Semantic]
    )
    Vector("sbt-1.4-jdk8", "sbt-1-jdk8", "sbt-1-jdk17", FutureProfile).foreach(profile =>
      Assert.assertEquals(ExactTranscript,
        SbtSpecs2SemanticContracts.Descriptions.forRuntimeProfile(profile)))
  }

  @Test def allNineCoordinatesAcceptTheirProfileSpecificShape(): Unit = {
    AllProfiles.foreach { profile =>
      verify(ignoredTranscript(profile),
        SbtSpecs2SemanticContracts.IgnoredTests.forRuntimeProfile(profile))
      verify(exampleTranscript,
        SbtSpecs2SemanticContracts.TestOnlyExamples.forRuntimeProfile(profile))
    }
    verify(exampleTranscript,
      SbtSpecs2SemanticContracts.Descriptions.forRuntimeProfile("sbt-2-jdk17"))

    verify(ignoredTranscript("sbt-1-jdk17", includeBridge = true),
      SbtSpecs2SemanticContracts.IgnoredTests.forRuntimeProfile("sbt-1-jdk17"))
    verify(ignoredTranscript("sbt-2-jdk17", includeBridge = true),
      SbtSpecs2SemanticContracts.IgnoredTests.forRuntimeProfile("sbt-2-jdk17"))
  }

  @Test def ignoredReportKeepsExactTextNamesAndOutcomesWithOnlyDurationsStructural(): Unit = {
    val profile = "sbt-2-jdk17"
    val mode = SbtSpecs2SemanticContracts.IgnoredTests.forRuntimeProfile(profile)
    val valid = ignoredTranscript(profile)
    verify(updateUnique(valid, _.contains("Finished in 125 ms"), "Specs2 duration",
      _.replace("125 ms", "999 ms")), mode)

    val invalidDuration = updateUnique(valid, _.contains("Finished in 125 ms"), "Specs2 duration",
      _.replace("125 ms", "quickly"))
    val wrongText = updateUnique(valid, _.contains("3 examples, 0 failure"), "Specs2 result summary",
      _.replace("3 skipped", "2 skipped"))
    val missingIgnored = valid.filterNot(line =>
      line.startsWith("##teamcity[testIgnored") && line.contains("contain 11 characters"))
    val wrongName = updateUnique(valid,
      line => line.startsWith("##teamcity[testStarted") && line.contains("contain 11 characters"),
      "first ignored example",
      _.replace("contain 11 characters", "contain eleven characters"))

    Vector(invalidDuration, wrongText, missingIgnored, wrongName).foreach(lines =>
      assertViolation(collect(lines, mode), SemanticCardinalityFailure))
  }

  @Test def testOnlyAndDescriptionsRequireEveryFullExampleNameAndStrictLifecycle(): Unit = {
    val mode = SbtSpecs2SemanticContracts.TestOnlyExamples.forRuntimeProfile("sbt-1-jdk17")
    val valid = exampleTranscript
    val duplicatedDescription = updateUnique(
      valid,
      line => line.startsWith("##teamcity[testStarted") && line.contains("start with"),
      "second Specs2 example",
      _.replace("start with", "start with start with")
    )
    val start = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testStarted") && line.contains("end with"), "third test start")
    val finish = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testFinished") && line.contains("end with"), "third test finish")
    val reversed = valid.updated(start, valid(finish)).updated(finish, valid(start))

    assertViolation(collect(duplicatedDescription, mode), SemanticCardinalityFailure)
    assertViolation(collect(reversed, mode), OrderingFailure)
  }

  @Test def compoundSpecs2MutationAggregatesAllIndependentFailures(): Unit = {
    val profile = "sbt-2-jdk17"
    val mode = SbtSpecs2SemanticContracts.IgnoredTests.forRuntimeProfile(profile)
    val valid = ignoredTranscript(profile)
    val start = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testStarted") && line.contains("start with"), "second test start")
    val finish = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testFinished") && line.contains("start with"), "second test finish")
    val wrongOrder = valid.updated(start, valid(finish)).updated(finish, valid(start))
    val wrongOwnership = updateUnique(wrongOrder, _.startsWith("##teamcity[testSuiteStarted"), "suite start",
      _.replace("flow-specs2", "flow-other"))
    val wrongLifecycle = updateUnique(wrongOwnership, _.startsWith("##teamcity[testSuiteFinished"), "suite finish",
      _.replace(IgnoredSuite, "BrokenSpec"))
    val wrongReport = updateUnique(wrongLifecycle,
      line => line.contains("text='|[info|] HelloWorldSpec'"), "first report line",
      _.replace("HelloWorldSpec", "ChangedSpec"))
    val mutated = wrongReport ++ Vector(
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
    assertBlocked(findings, OrderingFailure, "edge:suite-start->report-1")
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Violation]"))
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Blocked]"))
  }

  private def exampleTranscript: Vector[String] =
    Vector(suiteBoundary("testSuiteStarted", ExampleSuite)) ++
      ExampleNames.flatMap(passingTest) ++
      Vector(suiteBoundary("testSuiteFinished", ExampleSuite))

  private def ignoredTranscript(profile: String, includeBridge: Boolean = false): Vector[String] = {
    val sbt2 = isSbt2(profile)
    val mainSuffix = if (sbt2) "/target/out/jvm/scala-3.8.4/ignored-tests/classes ..."
    else "/target/scala-2.13/classes ..."
    val testSuffix = if (sbt2) "/target/out/jvm/scala-3.8.4/ignored-tests/test-classes ..."
    else "/target/scala-2.13/test-classes ..."
    val main = compilation(
      profile,
      "Scala compiler [specs2-ignored-tests]",
      "100:compile:compiler",
      mainSuffix,
      main = true,
      includeBridge = includeBridge
    )
    val test = compilation(
      profile,
      "Scala compiler in Test [specs2-ignored-tests]",
      "100:test:compiler",
      testSuffix,
      main = false,
      includeBridge = includeBridge
    )
    main ++ test ++ Vector(suiteBoundary("testSuiteStarted", IgnoredSuite)) ++
      reportLines(profile) ++ IgnoredNames.flatMap(ignoredTest) ++
      Vector(suiteBoundary("testSuiteFinished", IgnoredSuite)) ++
      Option.when(sbt2)(TaskSummary)
  }

  private def compilation(
    profile: String,
    compiler: String,
    flow: String,
    suffix: String,
    main: Boolean,
    includeBridge: Boolean
  ): Vector[String] = {
    val start = Vector(compilationBoundary("compilationStarted", compiler, flow))
    val info = Vector(message(flow, "NORMAL", s"[info] compiling 1 Scala source to /work$suffix"))
    val bridge = if (includeBridge) compilerBridge(profile, flow) else Vector.empty
    val warningsBeforeDone = if (main && isSbt2(profile)) Vector(
      inspectionType,
      message(flow, "WARNING", "[warn] there was 1 deprecation warning; re-run with -deprecation for details")
    ) else Vector.empty
    val warningsAfterDone = if (main && isSbt2(profile)) Vector(
      message(flow, "WARNING", "[warn] one warning found")
    ) else Vector.empty
    start ++ info ++ bridge ++ warningsBeforeDone ++
      Vector(message(flow, "NORMAL", "[info] done compiling")) ++ warningsAfterDone ++
      Vector(compilationBoundary("compilationFinished", compiler, flow))
  }

  private def compilerBridge(profile: String, flow: String): Vector[String] = {
    val (module, scalaVersion) = if (isSbt2(profile)) "compiler-bridge_3" -> "3.8.4"
    else "compiler-bridge_2.13" -> "2.13.18"
    Vector(
      message(flow, "NORMAL", s"[info] Non-compiled module '$module' for Scala $scalaVersion. Compiling..."),
      message(flow, "NORMAL", "[info]   Compilation completed in 1.25s.")
    )
  }

  private def reportLines(profile: String): Vector[String] = {
    val flow = s"100:test:general:${if (isSbt2(profile)) "testQuick" else "test"}"
    val texts = if (isSbt2(profile)) Vector(
      "[info] HelloWorldSpec",
      "[info]  ",
      "[info] The 'Hello world' string should",
      "[info]   o contain 11 characters",
      "[info] SKIPPED",
      "[info]   o start with 'Hello'",
      "[info] SKIPPED",
      "[info]   o end with 'world'",
      "[info] SKIPPED",
      "[info]  ",
      "[info]  ",
      "[info] Total for specification HelloWorldSpec",
      "[info] Finished in 125 ms",
      "[info] 3 examples, 0 failure, 0 error, 3 skipped",
      "[info]  "
    ) else Vector(
      "[info] HelloWorldSpec",
      "[info] ",
      "[info] The 'Hello world' string should",
      "[info]   o contain 11 characters\n[info] SKIPPED",
      "[info]   o start with 'Hello'\n[info] SKIPPED",
      "[info]   o end with 'world'\n[info] SKIPPED",
      "[info] ",
      "[info] ",
      "[info] Total for specification HelloWorldSpec",
      "[info] Finished in 125 ms\n[info] 3 examples, 0 failure, 0 error, 3 skipped",
      "[info] "
    )
    texts.map(message(flow, "NORMAL", _))
  }

  private def passingTest(name: String): Vector[String] = Vector(
    service("testStarted", "name" -> name,
      "captureStandardOutput" -> "true", "flowId" -> "flow-specs2"),
    service("testFinished", "name" -> name,
      "duration" -> "8", "flowId" -> "flow-specs2")
  )

  private def ignoredTest(name: String): Vector[String] = Vector(
    service("testStarted", "name" -> name,
      "captureStandardOutput" -> "true", "flowId" -> "flow-specs2"),
    service("testIgnored", "name" -> name, "flowId" -> "flow-specs2"),
    service("testFinished", "name" -> name,
      "duration" -> "8", "flowId" -> "flow-specs2")
  )

  private def suiteBoundary(kind: String, name: String): String =
    service(kind, "name" -> name, "flowId" -> "flow-specs2")

  private def compilationBoundary(kind: String, compiler: String, flow: String): String =
    service(kind, "compiler" -> compiler, "flowId" -> flow)

  private def message(flow: String, status: String, text: String): String =
    service("message", "status" -> status, "flowId" -> flow, "text" -> text)

  private def inspectionType: String = service("inspectionType",
    "id" -> "SbtCompileProblem",
    "name" -> "sbt compile problem",
    "description" -> "Compile problems",
    "category" -> "Compile problems")

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

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

  private def effectiveContract(mode: SbtOutputVerification): SbtSemanticContract = mode match {
    case Semantic(contract) => contract
    case other => throw new AssertionError(s"Expected semantic mode, got $other")
  }

  private def verify(lines: Vector[String], mode: SbtOutputVerification): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      effectiveContract(mode),
      exitCode = 0,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(
    lines: Vector[String],
    mode: SbtOutputVerification
  ): Vector[SbtSemanticFailure] = SbtSemanticOutputVerifier.collect(
    lines,
    effectiveContract(mode),
    exitCode = 0,
    delegated = SbtDelegatedVerification(processResultVerified = true)
  )

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
