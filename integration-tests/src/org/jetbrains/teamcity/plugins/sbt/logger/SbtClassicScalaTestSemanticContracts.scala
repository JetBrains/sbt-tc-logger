package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtClassicScalaTestSemanticContracts {
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  private val PassScenario = "scalatest-pass-and-failure"
  private val NestedScenario = "scalatest-nested-suites"
  private val LongNamesScenario = "scalatest-long-names"
  private val ErrorLikeScenario = "scalatest-error-like-output"
  private val InitializerScenario = "initializer-error-suite-construction"

  lazy val PassAndFailure: SbtOutputVerificationSelection = semanticSelection(PassScenario, AllProfiles)
  lazy val NestedSuites: SbtOutputVerificationSelection = semanticSelection(NestedScenario, AllProfiles)
  lazy val LongNames: SbtOutputVerificationSelection = semanticSelection(LongNamesScenario, AllProfiles)
  lazy val ErrorLikeOutput: SbtOutputVerificationSelection = hybridSelection(ErrorLikeScenario, Jdk17Profiles)
  lazy val InitializerError: SbtOutputVerificationSelection = semanticSelection(InitializerScenario, Jdk17Profiles)

  private final case class Profile(
    id: String,
    isSbt2: Boolean,
    testTask: String,
    passFailurePrefix: String,
    passFailureUserFrame: String
  )

  private final case class FailureSpec(
    prefix: String,
    userFrames: Vector[String]
  )

  private final case class TestSpec(
    id: String,
    name: String,
    failure: Option[FailureSpec] = None
  )

  private final case class SuiteSpec(
    id: String,
    name: String,
    tests: Vector[TestSpec]
  )

  private final case class TestPart(
    events: Vector[ExpectedSemanticEvent],
    lifecycle: SemanticLifecycleRule,
    start: SemanticEventId,
    finish: SemanticEventId
  )

  private final case class SuitePart(
    events: Vector[ExpectedSemanticEvent],
    lifecycles: Vector[SemanticLifecycleRule],
    edges: Set[HappensBefore],
    flow: SemanticBindingKey,
    start: SemanticEventId,
    finish: SemanticEventId,
    tests: Vector[TestPart]
  )

  private final case class CompilationPart(
    events: Vector[ExpectedSemanticEvent],
    edges: Set[HappensBefore],
    lifecycle: SemanticLifecycleRule,
    optionalGroups: Vector[OptionalSemanticEventGroup],
    buildId: SemanticBindingKey,
    start: SemanticEventId,
    finish: SemanticEventId
  )

  private val Sbt1FailurePrefix = "org.scalatest.exceptions.TestFailedException: true was not false"
  private val Sbt1FailureFrame = "\tat ListFlatSpec.$anonfun$new$2(ListFlatSpec.scala:14)"
  private val Sbt2FailurePrefix = "org.scalatest.exceptions.TestFailedException: true was not equal to false"
  private val Sbt2FailureFrame = "\tat ListFlatSpec.$anonfun$new$2(ListFlatSpec.scala:13)"
  private val MaximumScalaTestFrameworkFrames = 128

  private val Sbt1_4 = Profile(
    "sbt-1.4-jdk8",
    isSbt2 = false,
    testTask = "test",
    Sbt1FailurePrefix,
    Sbt1FailureFrame
  )
  private val Sbt1Jdk8 = Sbt1_4.copy(id = "sbt-1-jdk8")
  private val Sbt1Jdk17 = Sbt1_4.copy(id = "sbt-1-jdk17")
  private val Sbt2Jdk17 = Profile(
    "sbt-2-jdk17",
    isSbt2 = true,
    testTask = "testQuick",
    Sbt2FailurePrefix,
    Sbt2FailureFrame
  )

  private val AllProfiles = Vector(Sbt1_4, Sbt1Jdk8, Sbt1Jdk17, Sbt2Jdk17)
  private val Jdk17Profiles = Vector(Sbt1Jdk17, Sbt2Jdk17)
  private val ProfilesById = AllProfiles.map(profile => profile.id -> profile).toMap

  private[logger] def semanticContractFor(
    scenarioId: String,
    runtimeProfile: String
  ): SbtSemanticContract = {
    val profile = ProfilesById.getOrElse(
      runtimeProfile,
      throw new IllegalArgumentException(s"No classic ScalaTest semantic profile for '$runtimeProfile'.")
    )
    scenarioId match {
      case PassScenario => passAndFailureContract(profile)
      case NestedScenario => nestedSuitesContract(profile)
      case LongNamesScenario => longNamesContract(profile)
      case ErrorLikeScenario => errorLikeContract(profile)
      case InitializerScenario => initializerContract(profile)
      case other => throw new IllegalArgumentException(s"No classic ScalaTest semantic scenario for '$other'.")
    }
  }

  private def semanticSelection(
    scenarioId: String,
    profiles: Vector[Profile]
  ): SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = ExactTranscript,
    runtimeProfileOverrides = profiles.map(profile =>
      profile.id -> Semantic(semanticContractFor(scenarioId, profile.id))
    ).toMap
  )

  private def hybridSelection(
    scenarioId: String,
    profiles: Vector[Profile]
  ): SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = ExactTranscript,
    runtimeProfileOverrides = profiles.map { profile =>
      profile.id -> Hybrid(
        semanticContractFor(scenarioId, profile.id),
        errorLikePlainOutput(profile)
      )
    }.toMap
  )

  private def passAndFailureContract(profile: Profile): SbtSemanticContract = {
    val failure = FailureSpec(profile.passFailurePrefix, Vector(profile.passFailureUserFrame))
    val list = suitePart(SuiteSpec(
      "list-flat-spec",
      "ListFlatSpec",
      Vector(
        TestSpec("list-length", "ListFlatSpec.A List should have length as count of elements in it"),
        TestSpec(
          "list-contains",
          "ListFlatSpec.A List should contains elements passed in the factory method",
          Some(failure)
        ),
        TestSpec(
          "list-bounds",
          "ListFlatSpec.A List should throw IndexOutOfBounds exception when index is out of bounds"
        )
      )
    ))
    val example = suitePart(SuiteSpec(
      "example-spec",
      "ExampleSpec",
      Vector(
        TestSpec(
          "stack-order",
          "ExampleSpec.A Stack should pop values in last-in-first-out order"
        ),
        TestSpec(
          "stack-empty",
          "ExampleSpec.A Stack should throw NoSuchElementException if an empty stack is popped"
        )
      )
    ))
    val suites = Vector(list, example)
    val buildId = SemanticBindingKey.buildId("scalatest-pass-build")
    val taskFailure = ExpectedSemanticEvent("test-task-failure", BuildLogMessage,
      "status" -> exact("ERROR"),
      "flowId" -> buildFlow(buildId, profile.testTask),
      "text" -> exact(s"[error] (Test / ${profile.testTask}) sbt.TestsFailedException: Tests unsuccessful"))

    SbtSemanticContract(
      events = suites.flatMap(_.events) :+ taskFailure,
      happensBefore = suites.flatMap(_.edges).toSet ++
        suites.map(suite => HappensBefore(suite.finish, taskFailure.id)),
      lifecycles = suites.flatMap(_.lifecycles),
      distinctBindings = Set(DistinctSemanticBindings(list.flow, example.flow)),
      plainOutput = taskSummaries(if (profile.isSbt2) 1 else 0)
    )
  }

  private def nestedSuitesContract(profile: Profile): SbtSemanticContract = {
    val compilation = standardTestCompilation(
      NestedScenario,
      profile,
      sourceCount = 1,
      outputSuffix = if (profile.isSbt2)
        "/target/out/jvm/scala-3.8.4/scalatest-nested-suites/test-classes ..."
      else "/target/scala-2.13/test-classes ..."
    )
    val suites = Vector(
      asciiSuite,
      directAsciiSuite("a-suite", "ASuite", "A", "41", "a", "61"),
      directAsciiSuite("b-suite", "BSuite", "B", "42", "b", "62"),
      directAsciiSuite("c-suite", "CSuite", "C", "43", "c", "63")
    ).map(suitePart)
    val outputFlow = buildFlow(compilation.buildId, profile.testTask)
    val outputBySuite = Vector(
      suites(0) -> Vector(
        "ASuite:",
        "- A should have ASCII value 41 hex",
        "- a should have ASCII value 61 hex",
        "BSuite:",
        "- B should have ASCII value 42 hex",
        "- b should have ASCII value 62 hex",
        "CSuite:",
        "- C should have ASCII value 43 hex",
        "- c should have ASCII value 63 hex"
      ),
      suites(1) -> Vector("ASuite:"),
      suites(2) -> Vector("BSuite:"),
      suites(3) -> Vector("CSuite:")
    ).map { case (suite, texts) =>
      val events = texts.zipWithIndex.map { case (text, index) =>
        ExpectedSemanticEvent(s"${suite.start.value}-output-${index + 1}", BuildLogMessage,
          "status" -> exact("NORMAL"),
          "flowId" -> outputFlow,
          "text" -> exact(s"[info] $text"))
      }
      suite -> events
    }
    val suiteOrder = suites.sliding(2).collect { case Vector(before, after) =>
      HappensBefore(before.finish, after.start)
    }.toSet
    val outputEdges = outputBySuite.flatMap { case (suite, output) =>
      val firstTest = suite.tests.head.start
      chainEdges(Vector(suite.start) ++ output.map(_.id) :+ firstTest)
    }.toSet
    val distinctSuiteFlows = suites.indices.flatMap { first =>
      ((first + 1) until suites.size).map(second =>
        DistinctSemanticBindings(suites(first).flow, suites(second).flow))
    }.toSet

    SbtSemanticContract(
      events = compilation.events ++ suites.flatMap(_.events) ++ outputBySuite.flatMap(_._2),
      happensBefore = compilation.edges ++ suites.flatMap(_.edges) ++ suiteOrder ++ outputEdges +
        HappensBefore(compilation.finish, suites.head.start),
      lifecycles = Vector(compilation.lifecycle) ++ suites.flatMap(_.lifecycles),
      optionalGroups = compilation.optionalGroups,
      distinctBindings = distinctSuiteFlows,
      plainOutput = taskSummaries(if (profile.isSbt2) 1 else 0)
    )
  }

  private def longNamesContract(profile: Profile): SbtSemanticContract = {
    val suiteName = "com.jetbrains.teamcity.specs.some_name.some_group.MyTestModeSpec"
    val suite = suitePart(SuiteSpec(
      "long-names-suite",
      suiteName,
      Vector(
        TestSpec(
          "long-name-1",
          s"$suiteName.Feature: Some Advanced Mode Scenario: XYZ-1: Lorem ipsum dolor sit amet, " +
            "consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua"
        ),
        TestSpec(
          "long-name-2",
          s"$suiteName.Feature: Some Advanced Mode Scenario: XYZ-2: Lorem Ipsum is simply dummy text " +
            "of the printing and typesetting industry."
        )
      )
    ))
    val compilation = Option.unless(profile.isSbt2)(longNamesWarningCompilation(profile))

    SbtSemanticContract(
      events = compilation.toVector.flatMap(_.events) ++ suite.events,
      happensBefore = compilation.toVector.flatMap(_.edges).toSet ++ suite.edges ++
        compilation.map(part => HappensBefore(part.finish, suite.start)),
      lifecycles = compilation.toVector.map(_.lifecycle) ++ suite.lifecycles,
      optionalGroups = compilation.toVector.flatMap(_.optionalGroups)
    )
  }

  private def errorLikeContract(profile: Profile): SbtSemanticContract = {
    require(Jdk17Profiles.contains(profile), s"The error-like fixture is not supported on ${profile.id}.")
    val compilation = errorLikeWarningCompilation(profile)
    val runs = Vector(1, 2).map { invocation =>
      errorLikeSuitePart(
        invocation,
        SemanticBindingKey.flow(s"error-like-suite-$invocation-flow")
      )
    }

    SbtSemanticContract(
      events = compilation.events ++ runs.flatMap(_.events),
      happensBefore = compilation.edges ++ runs.flatMap(_.edges) +
        HappensBefore(compilation.finish, runs.head.start) +
        HappensBefore(runs.head.finish, runs.last.start),
      lifecycles = Vector(compilation.lifecycle) ++ runs.flatMap(_.lifecycles),
      distinctBindings = Set(DistinctSemanticBindings(runs.head.flow, runs.last.flow)),
      plainOutput = PlainOutputContract.DelegatedToHybrid
    )
  }

  private def initializerContract(profile: Profile): SbtSemanticContract = {
    require(Jdk17Profiles.contains(profile), s"The initializer-error fixture is not supported on ${profile.id}.")
    val suiteFlow = SemanticBindingKey.flow("initializer-suite-flow")
    val buildId = SemanticBindingKey.buildId("initializer-build")
    val task = if (profile.isSbt2) "testQuick" else "executeTests"
    val ownership = buildFlow(buildId, task)
    val start = ExpectedSemanticEvent("initializer-suite-start", TestSuiteStarted,
      "name" -> exact("InitializerErrorSuite"),
      "flowId" -> bound(suiteFlow))
    val topFrames = Vector("\tat InitializerErrorSuite.<init>(InitializerErrorSuite.scala:4)")
    val causeFrames = if (profile.isSbt2) Vector(
      "\tat InitializerErrorFixture$.<init>(InitializerErrorSuite.scala:12)",
      "\tat InitializerErrorFixture$.<clinit>(InitializerErrorSuite.scala)",
      "\tat InitializerErrorSuite.<init>(InitializerErrorSuite.scala:4)"
    ) else Vector(
      "\tat InitializerErrorFixture$.<clinit>(InitializerErrorSuite.scala:12)",
      "\tat InitializerErrorSuite.<init>(InitializerErrorSuite.scala:4)"
    )
    val detail = ExpectedSemanticEvent("initializer-detail", BuildLogMessage,
      "status" -> exact("ERROR"),
      "flowId" -> ownership,
      "text" -> linePrefixedThrowableChain(
        linePrefix = "[error] ",
        topException = "java.lang.ExceptionInInitializerError",
        topMessage = "",
        requiredTopUserFrames = topFrames,
        causeException = "java.lang.IllegalStateException",
        causeMessage = "Suite construction failure for #12",
        requiredCauseUserFrames = causeFrames,
        framework = RecognizedTestFramework.ScalaTest,
        maximumRecognizedFrames = 64
      ))
    val taskFailure = ExpectedSemanticEvent("initializer-task-failure", BuildLogMessage,
      "status" -> exact("ERROR"),
      "flowId" -> ownership,
      "text" -> exact(s"[error] (Test / $task) java.lang.ExceptionInInitializerError"))

    SbtSemanticContract(
      events = Vector(start, detail, taskFailure),
      happensBefore = chainEdges(Vector(start.id, detail.id, taskFailure.id)),
      // Suite construction fails after the start callback; absence of tests and suiteFinished is intentional.
      lifecycles = Vector.empty,
      plainOutput = taskSummaries(if (profile.isSbt2) 1 else 0)
    )
  }

  private def errorLikeSuitePart(invocation: Int, flow: SemanticBindingKey): SuitePart = {
    val suiteName = "TestSpec"
    val testNames = Vector(
      "Some Test stat warnings",
      "Some Test should print warnings",
      "Some Test run something",
      "Some Test log warning",
      "Some Test log error"
    )
    val start = ExpectedSemanticEvent.occurrence(
      s"error-like-suite-$invocation-start",
      TestSuiteStarted,
      invocation,
      "name" -> exact(suiteName)
    )
    val tests = testNames.zipWithIndex.map { case (shortName, index) =>
      val ordinal = (invocation - 1) * testNames.size + index + 1
      occurrenceTestPart(
        id = s"error-like-suite-$invocation-test-${index + 1}",
        name = s"$suiteName.$shortName",
        flow = flow,
        ordinal = ordinal
      )
    }
    val finish = ExpectedSemanticEvent.occurrence(
      s"error-like-suite-$invocation-finish",
      TestSuiteFinished,
      invocation,
      "name" -> exact(suiteName)
    )
    val childEvents = tests.flatMap(_.events)
    val siblingEdges = tests.sliding(2).collect { case Vector(before, after) =>
      HappensBefore(before.finish, after.start)
    }.toSet
    SuitePart(
      events = Vector(start) ++ childEvents ++ Vector(finish),
      lifecycles = Vector(SemanticLifecycleRule.suite(
        s"error-like-suite-$invocation",
        start.id.value,
        childEvents.map(_.id.value),
        finish.id.value,
        flow
      )) ++ tests.map(_.lifecycle),
      edges = siblingEdges,
      flow = flow,
      start = start.id,
      finish = finish.id,
      tests = tests
    )
  }

  private def occurrenceTestPart(
    id: String,
    name: String,
    flow: SemanticBindingKey,
    ordinal: Int
  ): TestPart = {
    val start = ExpectedSemanticEvent.occurrence(
      s"$id-start",
      TestStarted,
      ordinal,
      "name" -> exact(name),
      "captureStandardOutput" -> exact("true")
    )
    val finish = ExpectedSemanticEvent.occurrence(
      s"$id-finish",
      TestFinished,
      ordinal,
      "name" -> exact(name),
      "duration" -> unsignedDuration
    )
    TestPart(
      Vector(start, finish),
      SemanticLifecycleRule.test(id, start.id.value, Seq.empty, finish.id.value, flow),
      start.id,
      finish.id
    )
  }

  private def errorLikePlainOutput(profile: Profile): PlainOutputContract = {
    val thread = SemanticBindingKey.logbackThread("error-like-logback-thread")
    val warnSuffix = if (profile.isSbt2)
      "  TestSpec - WARNING: Invalid blah-blah-blah"
    else " TestSpec -- WARNING: Invalid blah-blah-blah"
    val errorSuffix = if (profile.isSbt2)
      " TestSpec - [error] some error in test output"
    else " TestSpec -- [error] some error in test output"
    val oneInvocation = Vector[PlainOutputPattern](
      PlainOutputPattern.Exact("WARNING: Invalid stat name /127.0.0.1:4010_backoffs exported as _127_0_0_1_4010_backoffs"),
      PlainOutputPattern.Exact("WARNING 3/19/14 1:26 PM:liquidbase: modifyDataType will lose primary key/autoincrement/not null settings for mysql"),
      PlainOutputPattern.Exact("Invalid stat name /127..."),
      PlainOutputPattern.Exact("- About to log waring!"),
      ScalaTestLogbackLine(thread, LogbackLevel.Warn, warnSuffix),
      PlainOutputPattern.Exact("- About to log error!"),
      ScalaTestLogbackLine(thread, LogbackLevel.Error, errorSuffix)
    )
    val patterns = Vector(1, 2).flatMap { invocation =>
      oneInvocation.map(pattern => BeforeFirstTestInSuite("TestSpec", invocation, pattern))
    }
    PlainOutputContract.Patterns(patterns)
  }

  private def asciiSuite: SuiteSpec = SuiteSpec(
    "ascii-suite",
    "ASCIISuite",
    Vector(
      asciiTest("ascii-a-upper", "ASCIISuite.ASuite", "A", "41"),
      asciiTest("ascii-a-lower", "ASCIISuite.ASuite", "a", "61"),
      asciiTest("ascii-b-upper", "ASCIISuite.BSuite", "B", "42"),
      asciiTest("ascii-b-lower", "ASCIISuite.BSuite", "b", "62"),
      asciiTest("ascii-c-upper", "ASCIISuite.CSuite", "C", "43"),
      asciiTest("ascii-c-lower", "ASCIISuite.CSuite", "c", "63")
    )
  )

  private def directAsciiSuite(
    id: String,
    name: String,
    upper: String,
    upperHex: String,
    lower: String,
    lowerHex: String
  ): SuiteSpec = SuiteSpec(
    id,
    name,
    Vector(
      asciiTest(s"$id-upper", name, upper, upperHex),
      asciiTest(s"$id-lower", name, lower, lowerHex)
    )
  )

  private def asciiTest(id: String, suiteName: String, character: String, hex: String): TestSpec =
    TestSpec(id, s"$suiteName.$character should have ASCII value $hex hex")

  private def suitePart(spec: SuiteSpec): SuitePart = {
    val flow = SemanticBindingKey.flow(s"${spec.id}-flow")
    val start = ExpectedSemanticEvent(s"${spec.id}-start", TestSuiteStarted,
      "name" -> exact(spec.name))
    val tests = spec.tests.map(testPart(_, flow))
    val finish = ExpectedSemanticEvent(s"${spec.id}-finish", TestSuiteFinished,
      "name" -> exact(spec.name))
    val childEvents = tests.flatMap(_.events)
    val siblingEdges = tests.sliding(2).collect { case Vector(before, after) =>
      HappensBefore(before.finish, after.start)
    }.toSet
    SuitePart(
      events = Vector(start) ++ childEvents ++ Vector(finish),
      lifecycles = Vector(SemanticLifecycleRule.suite(
        spec.id,
        start.id.value,
        childEvents.map(_.id.value),
        finish.id.value,
        flow
      )) ++ tests.map(_.lifecycle),
      edges = siblingEdges,
      flow = flow,
      start = start.id,
      finish = finish.id,
      tests = tests
    )
  }

  private def testPart(spec: TestSpec, flow: SemanticBindingKey): TestPart = {
    val start = ExpectedSemanticEvent(s"${spec.id}-start", TestStarted,
      "name" -> exact(spec.name),
      "captureStandardOutput" -> exact("true"))
    val failure = spec.failure.map(value => ExpectedSemanticEvent(s"${spec.id}-failure", TestFailed,
      "name" -> exact(spec.name),
      "details" -> userFailure(
        value.prefix,
        value.userFrames,
        RecognizedTestFramework.ScalaTest,
        maximumFrameworkFrames = MaximumScalaTestFrameworkFrames
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
      ),
      start.id,
      finish.id
    )
  }

  private def standardTestCompilation(
    scenarioId: String,
    profile: Profile,
    sourceCount: Int,
    outputSuffix: String
  ): CompilationPart = {
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val outputBase = SemanticBindingKey.path(s"$scenarioId-output-base")
    val ownership = embedded("", buildId, ":test:compiler")
    val compiler = s"Scala compiler in Test [$scenarioId]"
    val start = ExpectedSemanticEvent(s"$scenarioId-compile-start", CompilationStarted,
      "compiler" -> exact(compiler))
    val sourceLabel = if (sourceCount == 1) "1 Scala source" else s"$sourceCount Scala sources"
    val info = ExpectedSemanticEvent(s"$scenarioId-compile-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded(s"[info] compiling $sourceLabel to ", outputBase, outputSuffix))
    val done = ExpectedSemanticEvent(s"$scenarioId-compile-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> exact("[info] done compiling"))
    val finish = ExpectedSemanticEvent(s"$scenarioId-compile-finish", CompilationFinished,
      "compiler" -> exact(compiler))
    val announcement = ExpectedSemanticEvent(s"$scenarioId-bridge-announcement", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> compilerBridgeAnnouncement)
    val completion = ExpectedSemanticEvent(s"$scenarioId-bridge-completion", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> compilerBridgeCompletion)

    CompilationPart(
      events = Vector(start, info, done, finish),
      edges = Set(HappensBefore(info.id, done.id)),
      lifecycle = SemanticLifecycleRule.compilation(
        s"$scenarioId-test-compilation",
        start.id.value,
        Seq(info.id.value, done.id.value),
        finish.id.value,
        ownership
      ),
      optionalGroups = Vector(OptionalSemanticEventGroup(
        s"$scenarioId-compiler-bridge",
        Vector(announcement, completion),
        Set(
          HappensBefore(info.id, announcement.id),
          HappensBefore(announcement.id, completion.id),
          HappensBefore(completion.id, done.id)
        ),
        Some(ownership)
      )),
      buildId = buildId,
      start = start.id,
      finish = finish.id
    )
  }

  private def longNamesWarningCompilation(profile: Profile): CompilationPart = {
    require(!profile.isSbt2, "The long-name warning compilation exists only on SBT 1 profiles.")
    val scenarioId = LongNamesScenario
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val ownership = embedded("", buildId, ":test:compiler")
    val compiler = s"Scala compiler in Test [$scenarioId]"
    val start = ExpectedSemanticEvent(s"$scenarioId-compile-start", CompilationStarted,
      "compiler" -> exact(compiler))
    val problemType = inspectionType(s"$scenarioId-problem-type")
    val detail = ExpectedSemanticEvent(s"$scenarioId-warning-detail", BuildLogMessage,
      "status" -> exact("WARNING"),
      "text" -> exact("[warn] 3 deprecations (since 3.1.0); re-run with -deprecation for details"))
    val count = ExpectedSemanticEvent(s"$scenarioId-warning-count", BuildLogMessage,
      "status" -> exact("WARNING"),
      "text" -> exact("[warn] one warning found"))
    val finish = ExpectedSemanticEvent(s"$scenarioId-compile-finish", CompilationFinished,
      "compiler" -> exact(compiler))
    val ordered = Vector(start, problemType, detail, count, finish)

    CompilationPart(
      events = ordered,
      edges = chainEdges(ordered.map(_.id)),
      lifecycle = SemanticLifecycleRule.compilation(
        s"$scenarioId-test-compilation",
        start.id.value,
        Seq(detail.id.value, count.id.value),
        finish.id.value,
        ownership
      ),
      optionalGroups = Vector.empty,
      buildId = buildId,
      start = start.id,
      finish = finish.id
    )
  }

  private def errorLikeWarningCompilation(profile: Profile): CompilationPart = {
    val scenarioId = ErrorLikeScenario
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val sourceBase = SemanticBindingKey.path(s"$scenarioId-source-base")
    val ownership = embedded("", buildId, ":test:compiler")
    val compiler = s"Scala compiler in Test [$scenarioId]"
    val problem = if (profile.isSbt2)
      "A pure expression does nothing in statement position"
    else "a pure expression does nothing in statement position"
    val sourceSuffix = "/src/test/scala/TestSpec.scala"
    val start = ExpectedSemanticEvent(s"$scenarioId-compile-start", CompilationStarted,
      "compiler" -> exact(compiler))
    val problemType = inspectionType(s"$scenarioId-problem-type")
    val inspection = ExpectedSemanticEvent(s"$scenarioId-inspection", Inspection,
      "SEVERITY" -> exact("WARNING"),
      "line" -> exact("11"),
      "typeId" -> exact("SbtCompileProblem"),
      "message" -> exact(problem),
      "file" -> embedded("", sourceBase, sourceSuffix))
    val detail = ExpectedSemanticEvent(s"$scenarioId-warning-detail", BuildLogMessage,
      "status" -> exact("WARNING"),
      "text" -> embedded(
        "[warn] ",
        sourceBase,
        s"$sourceSuffix:11: $problem\n" +
          "[warn]     \"Hello\"  //warning will be risen here\n" +
          "[warn]     ^"
      ))
    val count = ExpectedSemanticEvent(s"$scenarioId-warning-count", BuildLogMessage,
      "status" -> exact("WARNING"),
      "text" -> exact("[warn] one warning found"))
    val finish = ExpectedSemanticEvent(s"$scenarioId-compile-finish", CompilationFinished,
      "compiler" -> exact(compiler))
    val ordered = Vector(start, problemType, inspection, detail, count, finish)

    CompilationPart(
      events = ordered,
      edges = chainEdges(ordered.map(_.id)),
      lifecycle = SemanticLifecycleRule.compilation(
        s"$scenarioId-test-compilation",
        start.id.value,
        Seq(detail.id.value, count.id.value),
        finish.id.value,
        ownership
      ),
      optionalGroups = Vector.empty,
      buildId = buildId,
      start = start.id,
      finish = finish.id
    )
  }

  private def inspectionType(id: String): ExpectedSemanticEvent =
    ExpectedSemanticEvent(id, InspectionType,
      "id" -> exact("SbtCompileProblem"),
      "name" -> exact("sbt compile problem"),
      "description" -> exact("Compile problems"),
      "category" -> exact("Compile problems"))

  private def buildFlow(buildId: SemanticBindingKey, task: String): SemanticValuePattern =
    embedded("", buildId, s":test:general:$task")

  private def taskSummaries(count: Int): PlainOutputContract =
    if (count == 0) PlainOutputContract.RejectAll
    else PlainOutputContract.Patterns(Vector.fill(count)(AfterServiceMessages(SbtTaskSummary)))

  private def chainEdges(ids: Vector[SemanticEventId]): Set[HappensBefore] =
    ids.sliding(2).collect { case Vector(before, after) => HappensBefore(before, after) }.toSet
}
