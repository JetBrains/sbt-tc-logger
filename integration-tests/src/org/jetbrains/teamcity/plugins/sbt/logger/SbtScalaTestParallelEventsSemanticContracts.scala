package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtScalaTestParallelEventsSemanticContracts {
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  lazy val Failure: SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = Semantic(contract(OutputLayout.Sbt1, PlainOutputContract.RejectAll)),
    runtimeProfileOverrides = Map(
      "sbt-2-jdk17" -> Semantic(contract(
        OutputLayout.Sbt2,
        PlainOutputContract.Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
      ))
    )
  )

  private val FailurePrefix = "org.scalatest.exceptions.TestFailedException: Test failed"
  private val FailureUserFrame = "\tat tests.NonParallelTest.$anonfun$new$2(NonParallelTest.scala:12)"
  private val MaximumScalaTestFrameworkFrames = 128

  private enum OutputLayout {
    case Sbt1, Sbt2

    def outputSuffix: String = this match {
      case Sbt1 => "/target/scala-2.13/test-classes ..."
      case Sbt2 => "/target/out/jvm/scala-2.12.20/scalateamcitytestreporterbug/test-classes ..."
    }
  }

  private final case class TestSpec(id: String, name: String, failed: Boolean)
  private final case class SuiteSpec(id: String, name: String, tests: Vector[TestSpec])
  private final case class TestPart(events: Vector[ExpectedSemanticEvent], lifecycle: SemanticLifecycleRule)
  private final case class SuitePart(
    events: Vector[ExpectedSemanticEvent],
    lifecycles: Vector[SemanticLifecycleRule],
    flow: SemanticBindingKey,
    start: SemanticEventId
  )

  private val SuiteSpecs = Vector(
    nestedSuite("suite-non-parallel", "suites.NonParallelSuite"),
    nestedSuite("suite-parallel", "suites.ParallelSuite"),
    directSuite("direct-non-parallel", "tests.NonParallelTest"),
    directSuite("direct-parallel", "tests.ParallelTest")
  )

  private def nestedSuite(id: String, name: String): SuiteSpec = SuiteSpec(id, name, Vector(
    testSpec(id, "non-parallel-pass", s"$name.tests.NonParallelTest.should Write Passing Tests", failed = false),
    testSpec(id, "non-parallel-fail", s"$name.tests.NonParallelTest.should Write Failing Tests", failed = true),
    testSpec(id, "parallel-pass", s"$name.tests.ParallelTest.should Write Passing Tests", failed = false),
    testSpec(id, "parallel-fail", s"$name.tests.ParallelTest.should Write Failing Tests", failed = true)
  ))

  private def directSuite(id: String, name: String): SuiteSpec = SuiteSpec(id, name, Vector(
    testSpec(id, "pass", s"$name.should Write Passing Tests", failed = false),
    testSpec(id, "fail", s"$name.should Write Failing Tests", failed = true)
  ))

  private def testSpec(suiteId: String, role: String, name: String, failed: Boolean): TestSpec =
    TestSpec(s"$suiteId-$role", name, failed)

  private def isParallelTest(name: String): Boolean =
    name.startsWith("tests.ParallelTest.") || name.contains(".tests.ParallelTest.")

  private def contract(layout: OutputLayout, plainOutput: PlainOutputContract): SbtSemanticContract = {
    val outputBase = SemanticBindingKey.path("scalatest-parallel-output-base")
    val buildId = SemanticBindingKey.buildId("scalatest-parallel-build")
    val compileOwnership = embedded("", buildId, ":test:compiler")
    val compileStart = ExpectedSemanticEvent("compile-start", CompilationStarted,
      "compiler" -> exact("Scala compiler in Test [scalatest-parallel-events]"))
    val compileInfo = ExpectedSemanticEvent("compile-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded("[info] compiling 4 Scala sources to ", outputBase, layout.outputSuffix))
    val compileDone = ExpectedSemanticEvent("compile-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> exact("[info] done compiling"))
    val compileFinish = ExpectedSemanticEvent("compile-finish", CompilationFinished,
      "compiler" -> exact("Scala compiler in Test [scalatest-parallel-events]"))
    val bridgeAnnouncement = ExpectedSemanticEvent("compile-bridge-announcement", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> compilerBridgeAnnouncement)
    val bridgeCompletion = ExpectedSemanticEvent("compile-bridge-completion", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> compilerBridgeCompletion)
    val suites = SuiteSpecs.map(suitePart)
    val distinctSuiteFlows = suites.indices.flatMap { first =>
      ((first + 1) until suites.size).map(second =>
        DistinctSemanticBindings(suites(first).flow, suites(second).flow))
    }.toSet

    SbtSemanticContract(
      events = Vector(compileStart, compileInfo, compileDone, compileFinish) ++ suites.flatMap(_.events),
      happensBefore = Set(HappensBefore(compileInfo.id, compileDone.id)) ++
        suites.map(suite => HappensBefore(compileFinish.id, suite.start)),
      lifecycles = Vector(SemanticLifecycleRule.compilation(
        "test-compilation",
        compileStart.id.value,
        Seq(compileInfo.id.value, compileDone.id.value),
        compileFinish.id.value,
        compileOwnership
      )) ++ suites.flatMap(_.lifecycles),
      optionalGroups = Vector(OptionalSemanticEventGroup(
        "test-compilation-compiler-bridge",
        Vector(bridgeAnnouncement, bridgeCompletion),
        happensBefore = Set(
          HappensBefore(compileInfo.id, bridgeAnnouncement.id),
          HappensBefore(bridgeAnnouncement.id, bridgeCompletion.id),
          HappensBefore(bridgeCompletion.id, compileDone.id)
        ),
        ownership = Some(compileOwnership)
      )),
      distinctBindings = distinctSuiteFlows,
      plainOutput = plainOutput
    )
  }

  private def suitePart(spec: SuiteSpec): SuitePart = {
    val flow = SemanticBindingKey.flow(s"${spec.id}-flow")
    val start = ExpectedSemanticEvent(s"${spec.id}-start", TestSuiteStarted,
      "name" -> exact(spec.name))
    val finish = ExpectedSemanticEvent(s"${spec.id}-finish", TestSuiteFinished,
      "name" -> exact(spec.name))
    val tests = spec.tests.map(testPart(_, flow))
    val childEvents = tests.flatMap(_.events)
    val suiteLifecycle = SemanticLifecycleRule.suite(
      spec.id,
      start.id.value,
      childEvents.map(_.id.value),
      finish.id.value,
      flow
    )
    SuitePart(
      events = Vector(start) ++ childEvents ++ Vector(finish),
      lifecycles = suiteLifecycle +: tests.map(_.lifecycle),
      flow = flow,
      start = start.id
    )
  }

  private def testPart(spec: TestSpec, flow: SemanticBindingKey): TestPart = {
    val start = ExpectedSemanticEvent(s"${spec.id}-start", TestStarted,
      "name" -> exact(spec.name),
      "captureStandardOutput" -> exact("true"))
    val failure = Option.when(spec.failed)(ExpectedSemanticEvent(s"${spec.id}-failure", TestFailed,
      "name" -> exact(spec.name),
      "details" -> userFailure(
        FailurePrefix,
        Seq(FailureUserFrame),
        RecognizedTestFramework.ScalaTest,
        maximumFrameworkFrames = MaximumScalaTestFrameworkFrames,
        allowedGeneratedOwners = Option.when(isParallelTest(spec.name))("tests.ParallelTest").toSet
      )))
    val finish = ExpectedSemanticEvent(s"${spec.id}-finish", TestFinished,
      "name" -> exact(spec.name),
      "duration" -> unsignedDuration)
    val events = Vector(start) ++ failure ++ Vector(finish)
    TestPart(
      events,
      SemanticLifecycleRule.test(
        spec.id,
        start.id.value,
        failure.map(_.id.value).toVector,
        finish.id.value,
        flow
      )
    )
  }
}
