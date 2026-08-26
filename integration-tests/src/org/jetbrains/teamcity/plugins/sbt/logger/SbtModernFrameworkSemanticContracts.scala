package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtModernFrameworkSemanticContracts {
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  lazy val ScalaTest: SbtOutputVerificationSelection = selection(ScalaTestSpec)
  lazy val JUnit4: SbtOutputVerificationSelection = selection(JUnit4Spec)
  lazy val Jupiter: SbtOutputVerificationSelection = selection(JupiterSpec)
  lazy val MUnit: SbtOutputVerificationSelection = selection(MUnitSpec)

  private enum Outcome {
    case Passing
    case Ignored
    case Failed(details: SemanticValuePattern)
  }

  private final case class TestSpec(
    id: String,
    name: String,
    outcome: Outcome,
    exactDuration: Option[String] = None
  )

  private final case class FrameworkSpec(
    scenarioId: String,
    moduleName: String,
    suiteName: String,
    tests: Vector[TestSpec]
  )

  private final case class TestPart(
    events: Vector[ExpectedSemanticEvent],
    lifecycle: SemanticLifecycleRule,
    start: SemanticEventId,
    finish: SemanticEventId
  )

  private val Profiles = Vector("sbt-1-jdk17", "sbt-2-jdk17")
  private val MaximumFrameworkFrames = 128

  private val ScalaTestSpec = FrameworkSpec(
    "modern-scalatest",
    "modern-scalatest-reporting",
    "ModernScalaTestSuite",
    Vector(
      TestSpec("passing", "ModernScalaTestSuite.passing test", Outcome.Passing),
      TestSpec("failing", "ModernScalaTestSuite.failing test", Outcome.Failed(userFailure(
        "org.scalatest.exceptions.TestFailedException: intentional ScalaTest failure",
        Seq(
          "\tat ModernScalaTestSuite.testFun$proxy2$1(ModernScalaTestSuite.scala:9)",
          "\tat ModernScalaTestSuite.$init$$$anonfun$2(ModernScalaTestSuite.scala:8)"
        ),
        RecognizedTestFramework.ScalaTest,
        maximumFrameworkFrames = MaximumFrameworkFrames
      ))),
      TestSpec("ignored", "ModernScalaTestSuite.ignored test", Outcome.Ignored, exactDuration = Some("-1"))
    )
  )

  private val JUnit4Spec = FrameworkSpec(
    "modern-junit4",
    "modern-junit4-reporting",
    "ModernJUnit4Suite",
    Vector(
      TestSpec("failing", "ModernJUnit4Suite.failingTest", Outcome.Failed(userFailure(
        "java.lang.AssertionError: intentional JUnit 4 failure expected:<2> but was:<1>",
        Seq("\tat ModernJUnit4Suite.failingTest(ModernJUnit4Suite.scala:7)"),
        RecognizedTestFramework.JUnit,
        maximumFrameworkFrames = MaximumFrameworkFrames
      ))),
      TestSpec("ignored", "ModernJUnit4Suite.ignoredTest", Outcome.Ignored),
      TestSpec("passing", "ModernJUnit4Suite.passingTest", Outcome.Passing)
    )
  )

  private val JupiterSpec = FrameworkSpec(
    "modern-jupiter",
    "modern-jupiter-reporting",
    "ModernJupiterSuite",
    Vector(
      TestSpec("failing", "ModernJupiterSuite.failingTest()", Outcome.Failed(userFailure(
        "org.opentest4j.AssertionFailedError: intentional Jupiter failure ==> expected: <2> but was: <1>",
        Seq("\tat ModernJupiterSuite.failingTest(ModernJupiterSuite.scala:7)"),
        RecognizedTestFramework.JUnit,
        maximumFrameworkFrames = MaximumFrameworkFrames
      ))),
      TestSpec("ignored", "ModernJupiterSuite.ignoredTest()", Outcome.Ignored),
      TestSpec("passing", "ModernJupiterSuite.passingTest()", Outcome.Passing)
    )
  )

  private val MUnitDetails =
    "munit.ComparisonFailException: src/test/scala/ModernMUnitSuite.scala:7\n" +
      "6:  test(\"failing test\") {\n" +
      "7:    assertEquals(1, 2)\n" +
      "8:  }\n" +
      "values are not the same\n" +
      "=> Obtained\n" +
      "1\n" +
      "=> Diff (- expected, + obtained)\n" +
      "-2\n" +
      "+1\n" +
      "\tat munit.FunSuite.assertEquals(FunSuite.scala:12)\n" +
      "\tat ModernMUnitSuite.$init$$$anonfun$2(ModernMUnitSuite.scala:7)\n"

  private val MUnitSpec = FrameworkSpec(
    "modern-munit",
    "modern-munit-reporting",
    "ModernMUnitSuite",
    Vector(
      TestSpec("passing", "ModernMUnitSuite.passing test", Outcome.Passing),
      TestSpec("failing", "ModernMUnitSuite.failing test", Outcome.Failed(exact(MUnitDetails))),
      TestSpec("ignored", "ModernMUnitSuite.ignored test", Outcome.Ignored)
    )
  )

  private val SpecsByScenario = Vector(ScalaTestSpec, JUnit4Spec, JupiterSpec, MUnitSpec)
    .map(spec => spec.scenarioId -> spec).toMap

  private[logger] def semanticContractFor(
    scenarioId: String,
    runtimeProfile: String
  ): SbtSemanticContract = {
    if (!Profiles.contains(runtimeProfile)) {
      throw new IllegalArgumentException(s"No modern-framework semantic profile for '$runtimeProfile'.")
    }
    val spec = SpecsByScenario.getOrElse(
      scenarioId,
      throw new IllegalArgumentException(s"No modern-framework semantic scenario for '$scenarioId'.")
    )
    contract(spec, isSbt2 = runtimeProfile == "sbt-2-jdk17")
  }

  private def selection(spec: FrameworkSpec): SbtOutputVerificationSelection =
    SbtOutputVerificationSelection(
      defaultMode = ExactTranscript,
      runtimeProfileOverrides = Profiles.map(profile =>
        profile -> Semantic(semanticContractFor(spec.scenarioId, profile))
      ).toMap
    )

  private def contract(spec: FrameworkSpec, isSbt2: Boolean): SbtSemanticContract = {
    val buildId = SemanticBindingKey.buildId(s"${spec.scenarioId}-build")
    val outputBase = SemanticBindingKey.path(s"${spec.scenarioId}-output-base")
    val compileOwnership = embedded("", buildId, ":test:compiler")
    val compiler = s"Scala compiler in Test [${spec.scenarioId}]"
    val compileStart = ExpectedSemanticEvent("compile-start", CompilationStarted,
      "compiler" -> exact(compiler))
    val suffix = if (isSbt2)
      s"/target/out/jvm/scala-3.8.4/${spec.moduleName}/test-classes ..."
    else "/target/scala-3.8.4/test-classes ..."
    val compileInfo = ExpectedSemanticEvent("compile-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded("[info] compiling 1 Scala source to ", outputBase, suffix))
    val compileDone = ExpectedSemanticEvent("compile-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> exact("[info] done compiling"))
    val compileFinish = ExpectedSemanticEvent("compile-finish", CompilationFinished,
      "compiler" -> exact(compiler))
    val bridge = Vector(
      ExpectedSemanticEvent("bridge-announcement", BuildLogMessage,
        "status" -> exact("NORMAL"), "text" -> compilerBridgeAnnouncement),
      ExpectedSemanticEvent("bridge-completion", BuildLogMessage,
        "status" -> exact("NORMAL"), "text" -> compilerBridgeCompletion)
    )
    val suiteFlow = SemanticBindingKey.flow(s"${spec.scenarioId}-suite-flow")
    val suiteStart = ExpectedSemanticEvent("suite-start", TestSuiteStarted,
      "name" -> exact(spec.suiteName))
    val tests = spec.tests.map(testPart(_, suiteFlow))
    val children = tests.flatMap(_.events)
    val suiteFinish = ExpectedSemanticEvent("suite-finish", TestSuiteFinished,
      "name" -> exact(spec.suiteName))

    SbtSemanticContract(
      events = Vector(compileStart, compileInfo, compileDone, compileFinish, suiteStart) ++
        children ++ Vector(suiteFinish),
      happensBefore = Set(
        HappensBefore(compileInfo.id, compileDone.id),
        HappensBefore(compileFinish.id, suiteStart.id)
      ) ++ tests.sliding(2).collect { case Vector(before, after) =>
        HappensBefore(before.finish, after.start)
      }.toSet,
      lifecycles = Vector(
        SemanticLifecycleRule.compilation(
          "test-compilation",
          compileStart.id.value,
          Seq(compileInfo.id.value, compileDone.id.value),
          compileFinish.id.value,
          compileOwnership
        ),
        SemanticLifecycleRule.suite(
          "modern-framework-suite",
          suiteStart.id.value,
          children.map(_.id.value),
          suiteFinish.id.value,
          suiteFlow
        )
      ) ++ tests.map(_.lifecycle),
      optionalGroups = Vector(OptionalSemanticEventGroup(
        "test-compiler-bridge",
        bridge,
        Set(
          HappensBefore(compileInfo.id, bridge.head.id),
          HappensBefore(bridge.head.id, bridge.last.id),
          HappensBefore(bridge.last.id, compileDone.id)
        ),
        Some(compileOwnership)
      )),
      plainOutput = PlainOutputContract.Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
    )
  }

  private def testPart(spec: TestSpec, flow: SemanticBindingKey): TestPart = {
    val start = ExpectedSemanticEvent(s"${spec.id}-start", TestStarted,
      "name" -> exact(spec.name),
      "captureStandardOutput" -> exact("true"))
    val outcome = spec.outcome match {
      case Outcome.Passing => Vector.empty
      case Outcome.Ignored => Vector(ExpectedSemanticEvent(s"${spec.id}-outcome", TestIgnored,
        "name" -> exact(spec.name)))
      case Outcome.Failed(details) => Vector(ExpectedSemanticEvent(s"${spec.id}-outcome", TestFailed,
        "name" -> exact(spec.name),
        "details" -> details))
    }
    val duration = spec.exactDuration.fold[SemanticValuePattern](unsignedDuration)(exact)
    val finish = ExpectedSemanticEvent(s"${spec.id}-finish", TestFinished,
      "name" -> exact(spec.name),
      "duration" -> duration)
    val events = Vector(start) ++ outcome ++ Vector(finish)
    TestPart(
      events,
      SemanticLifecycleRule.test(
        spec.id,
        start.id.value,
        outcome.map(_.id.value),
        finish.id.value,
        flow
      ),
      start.id,
      finish.id
    )
  }
}
