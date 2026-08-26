package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtSpecs2SemanticContracts {
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  private val IgnoredScenario = "specs2-ignored-tests"
  private val TestOnlyScenario = "specs2-test-only"
  private val DescriptionsScenario = "specs2-descriptions"

  lazy val IgnoredTests: SbtOutputVerificationSelection = selection(IgnoredScenario, AllProfiles)
  lazy val TestOnlyExamples: SbtOutputVerificationSelection = selection(TestOnlyScenario, AllProfiles)
  lazy val Descriptions: SbtOutputVerificationSelection = selection(DescriptionsScenario, Vector(Sbt2Jdk17))

  private final case class Profile(id: String, isSbt2: Boolean)
  private final case class TestPart(
    events: Vector[ExpectedSemanticEvent],
    lifecycle: SemanticLifecycleRule,
    start: SemanticEventId,
    finish: SemanticEventId
  )
  private final case class CompilationPart(
    events: Vector[ExpectedSemanticEvent],
    edges: Set[HappensBefore],
    lifecycle: SemanticLifecycleRule,
    optionalGroup: OptionalSemanticEventGroup,
    start: SemanticEventId,
    finish: SemanticEventId
  )

  private val Sbt1_4 = Profile("sbt-1.4-jdk8", isSbt2 = false)
  private val Sbt1Jdk8 = Profile("sbt-1-jdk8", isSbt2 = false)
  private val Sbt1Jdk17 = Profile("sbt-1-jdk17", isSbt2 = false)
  private val Sbt2Jdk17 = Profile("sbt-2-jdk17", isSbt2 = true)
  private val AllProfiles = Vector(Sbt1_4, Sbt1Jdk8, Sbt1Jdk17, Sbt2Jdk17)
  private val ProfilesById = AllProfiles.map(profile => profile.id -> profile).toMap

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

  private[logger] def semanticContractFor(
    scenarioId: String,
    runtimeProfile: String
  ): SbtSemanticContract = {
    val profile = ProfilesById.getOrElse(
      runtimeProfile,
      throw new IllegalArgumentException(s"No Specs2 semantic profile for '$runtimeProfile'.")
    )
    scenarioId match {
      case IgnoredScenario => ignoredContract(profile)
      case TestOnlyScenario => exampleContract()
      case DescriptionsScenario if profile.isSbt2 => exampleContract()
      case DescriptionsScenario =>
        throw new IllegalArgumentException(s"Specs2 descriptions do not support '$runtimeProfile'.")
      case other => throw new IllegalArgumentException(s"No Specs2 semantic scenario for '$other'.")
    }
  }

  private def selection(
    scenarioId: String,
    profiles: Vector[Profile]
  ): SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = ExactTranscript,
    runtimeProfileOverrides = profiles.map(profile =>
      profile.id -> Semantic(semanticContractFor(scenarioId, profile.id))
    ).toMap
  )

  private def exampleContract(): SbtSemanticContract = {
    val flow = SemanticBindingKey.flow("specs2-example-flow")
    val start = ExpectedSemanticEvent("suite-start", TestSuiteStarted,
      "name" -> exact(ExampleSuite))
    val tests = ExampleNames.zipWithIndex.map { case (name, index) =>
      passingTest(s"example-${index + 1}", name, flow)
    }
    val finish = ExpectedSemanticEvent("suite-finish", TestSuiteFinished,
      "name" -> exact(ExampleSuite))
    val children = tests.flatMap(_.events)
    SbtSemanticContract(
      events = Vector(start) ++ children ++ Vector(finish),
      happensBefore = tests.sliding(2).collect { case Vector(before, after) =>
        HappensBefore(before.finish, after.start)
      }.toSet,
      lifecycles = Vector(SemanticLifecycleRule.suite(
        "specs2-example-suite",
        start.id.value,
        children.map(_.id.value),
        finish.id.value,
        flow
      )) ++ tests.map(_.lifecycle)
    )
  }

  private def ignoredContract(profile: Profile): SbtSemanticContract = {
    val buildId = SemanticBindingKey.buildId("specs2-ignored-build")
    val outputBase = SemanticBindingKey.path("specs2-ignored-output-base")
    val main = compilationPart(
      id = "main",
      compiler = "Scala compiler [specs2-ignored-tests]",
      ownership = embedded("", buildId, ":compile:compiler"),
      outputBase = outputBase,
      targetSuffix = if (profile.isSbt2)
        "/target/out/jvm/scala-3.8.4/ignored-tests/classes ..."
      else "/target/scala-2.13/classes ...",
      warningEvents = if (profile.isSbt2) sbt2MainWarnings else Vector.empty
    )
    val test = compilationPart(
      id = "test",
      compiler = "Scala compiler in Test [specs2-ignored-tests]",
      ownership = embedded("", buildId, ":test:compiler"),
      outputBase = outputBase,
      targetSuffix = if (profile.isSbt2)
        "/target/out/jvm/scala-3.8.4/ignored-tests/test-classes ..."
      else "/target/scala-2.13/test-classes ...",
      warningEvents = Vector.empty
    )
    val suiteFlow = SemanticBindingKey.flow("specs2-ignored-suite-flow")
    val suiteStart = ExpectedSemanticEvent("suite-start", TestSuiteStarted,
      "name" -> exact(IgnoredSuite))
    val report = ignoredReport(profile, buildId)
    val tests = IgnoredNames.zipWithIndex.map { case (name, index) =>
      ignoredTest(s"ignored-${index + 1}", name, suiteFlow)
    }
    val suiteFinish = ExpectedSemanticEvent("suite-finish", TestSuiteFinished,
      "name" -> exact(IgnoredSuite))
    val children = tests.flatMap(_.events)

    SbtSemanticContract(
      events = main.events ++ test.events ++ Vector(suiteStart) ++ report ++ children ++ Vector(suiteFinish),
      happensBefore = main.edges ++ test.edges ++ Set(
        HappensBefore(main.finish, test.start),
        HappensBefore(test.finish, suiteStart.id)
      ) ++ chainEdges(Vector(suiteStart.id) ++ report.map(_.id) :+ tests.head.start) ++
        tests.sliding(2).collect { case Vector(before, after) =>
          HappensBefore(before.finish, after.start)
        }.toSet,
      lifecycles = Vector(main.lifecycle, test.lifecycle, SemanticLifecycleRule.suite(
        "specs2-ignored-suite",
        suiteStart.id.value,
        children.map(_.id.value),
        suiteFinish.id.value,
        suiteFlow
      )) ++ tests.map(_.lifecycle),
      optionalGroups = Vector(main.optionalGroup, test.optionalGroup),
      plainOutput = if (profile.isSbt2)
        PlainOutputContract.Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
      else PlainOutputContract.RejectAll
    )
  }

  private def compilationPart(
    id: String,
    compiler: String,
    ownership: SemanticValuePattern,
    outputBase: SemanticBindingKey,
    targetSuffix: String,
    warningEvents: Vector[ExpectedSemanticEvent]
  ): CompilationPart = {
    val start = ExpectedSemanticEvent(s"$id-compile-start", CompilationStarted,
      "compiler" -> exact(compiler))
    val info = ExpectedSemanticEvent(s"$id-compile-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded("[info] compiling 1 Scala source to ", outputBase, targetSuffix))
    val done = ExpectedSemanticEvent(s"$id-compile-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> exact("[info] done compiling"))
    val finish = ExpectedSemanticEvent(s"$id-compile-finish", CompilationFinished,
      "compiler" -> exact(compiler))
    val orderedMiddle = Vector(info) ++ warningEvents.take(2) ++ Vector(done) ++ warningEvents.drop(2)
    val bridge = Vector(
      ExpectedSemanticEvent(s"$id-bridge-announcement", BuildLogMessage,
        "status" -> exact("NORMAL"), "text" -> compilerBridgeAnnouncement),
      ExpectedSemanticEvent(s"$id-bridge-completion", BuildLogMessage,
        "status" -> exact("NORMAL"), "text" -> compilerBridgeCompletion)
    )
    val afterBridge = orderedMiddle.drop(1).head.id

    CompilationPart(
      events = Vector(start) ++ orderedMiddle ++ Vector(finish),
      edges = chainEdges(orderedMiddle.map(_.id)),
      lifecycle = SemanticLifecycleRule.compilation(
        s"$id-compilation",
        start.id.value,
        orderedMiddle.filter(_.kind == BuildLogMessage).map(_.id.value),
        finish.id.value,
        ownership
      ),
      optionalGroup = OptionalSemanticEventGroup(
        s"$id-compiler-bridge",
        bridge,
        Set(
          HappensBefore(info.id, bridge.head.id),
          HappensBefore(bridge.head.id, bridge.last.id),
          HappensBefore(bridge.last.id, afterBridge)
        ),
        Some(ownership)
      ),
      start = start.id,
      finish = finish.id
    )
  }

  private def sbt2MainWarnings: Vector[ExpectedSemanticEvent] = Vector(
    ExpectedSemanticEvent("main-problem-type", InspectionType,
      "id" -> exact("SbtCompileProblem"),
      "name" -> exact("sbt compile problem"),
      "description" -> exact("Compile problems"),
      "category" -> exact("Compile problems")),
    ExpectedSemanticEvent("main-warning-detail", BuildLogMessage,
      "status" -> exact("WARNING"),
      "text" -> exact("[warn] there was 1 deprecation warning; re-run with -deprecation for details")),
    ExpectedSemanticEvent("main-warning-count", BuildLogMessage,
      "status" -> exact("WARNING"),
      "text" -> exact("[warn] one warning found"))
  )

  private def ignoredReport(
    profile: Profile,
    buildId: SemanticBindingKey
  ): Vector[ExpectedSemanticEvent] = {
    val task = if (profile.isSbt2) "testQuick" else "test"
    val ownership = embedded("", buildId, s":test:general:$task")
    val duration = SemanticBindingKey.duration("specs2-report-duration")
    val texts = if (profile.isSbt2) Vector[SemanticValuePattern](
      exact("[info] HelloWorldSpec"),
      exact("[info]  "),
      exact("[info] The 'Hello world' string should"),
      exact("[info]   o contain 11 characters"),
      exact("[info] SKIPPED"),
      exact("[info]   o start with 'Hello'"),
      exact("[info] SKIPPED"),
      exact("[info]   o end with 'world'"),
      exact("[info] SKIPPED"),
      exact("[info]  "),
      exact("[info]  "),
      exact("[info] Total for specification HelloWorldSpec"),
      embedded("[info] Finished in ", duration, " ms"),
      exact("[info] 3 examples, 0 failure, 0 error, 3 skipped"),
      exact("[info]  ")
    ) else Vector[SemanticValuePattern](
      exact("[info] HelloWorldSpec"),
      exact("[info] "),
      exact("[info] The 'Hello world' string should"),
      exact("[info]   o contain 11 characters\n[info] SKIPPED"),
      exact("[info]   o start with 'Hello'\n[info] SKIPPED"),
      exact("[info]   o end with 'world'\n[info] SKIPPED"),
      exact("[info] "),
      exact("[info] "),
      exact("[info] Total for specification HelloWorldSpec"),
      embedded(
        "[info] Finished in ",
        duration,
        " ms\n[info] 3 examples, 0 failure, 0 error, 3 skipped"
      ),
      exact("[info] ")
    )
    texts.zipWithIndex.map { case (text, index) =>
      ExpectedSemanticEvent(s"report-${index + 1}", BuildLogMessage,
        "status" -> exact("NORMAL"),
        "flowId" -> ownership,
        "text" -> text)
    }
  }

  private def passingTest(id: String, name: String, flow: SemanticBindingKey): TestPart = {
    val start = ExpectedSemanticEvent(s"$id-start", TestStarted,
      "name" -> exact(name),
      "captureStandardOutput" -> exact("true"))
    val finish = ExpectedSemanticEvent(s"$id-finish", TestFinished,
      "name" -> exact(name),
      "duration" -> unsignedDuration)
    TestPart(
      Vector(start, finish),
      SemanticLifecycleRule.test(id, start.id.value, Seq.empty, finish.id.value, flow),
      start.id,
      finish.id
    )
  }

  private def ignoredTest(id: String, name: String, flow: SemanticBindingKey): TestPart = {
    val start = ExpectedSemanticEvent(s"$id-start", TestStarted,
      "name" -> exact(name),
      "captureStandardOutput" -> exact("true"))
    val ignored = ExpectedSemanticEvent(s"$id-outcome", TestIgnored,
      "name" -> exact(name))
    val finish = ExpectedSemanticEvent(s"$id-finish", TestFinished,
      "name" -> exact(name),
      "duration" -> unsignedDuration)
    TestPart(
      Vector(start, ignored, finish),
      SemanticLifecycleRule.test(id, start.id.value, Seq(ignored.id.value), finish.id.value, flow),
      start.id,
      finish.id
    )
  }

  private def chainEdges(ids: Vector[SemanticEventId]): Set[HappensBefore] =
    ids.sliding(2).collect { case Vector(before, after) => HappensBefore(before, after) }.toSet
}
