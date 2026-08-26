package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtJUnitResultLoggerSemanticContracts {
  import JdkMethodReflectionFrameRole.*
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  private val TeamCityHiddenScenario = "junit-teamcity-result-no-task-output"
  private val ConfiguredTaskOutputScenario = "junit-configured-result-task-output"
  private val ConfiguredHiddenScenario = "junit-configured-result-no-task-output"
  private val CustomConfiguredScenario = "custom-result-logger-no-task-output"
  private val CustomTeamCityScenario = "custom-result-logger-teamcity-hidden"
  private val TestOnlyHiddenScenario = "junit-test-only-configured-hidden"
  private val TestQuickHiddenScenario = "junit-test-quick-configured-hidden"
  private val IntegrationHiddenScenario = "integration-test-quick-configured-hidden"
  private val TestFullHiddenScenario = "junit-test-full-configured-hidden"

  lazy val TeamCityResultHidden: SbtOutputVerificationSelection = selection(TeamCityHiddenScenario, AllProfiles)
  lazy val ConfiguredResultWithTaskOutput: SbtOutputVerificationSelection =
    selection(ConfiguredTaskOutputScenario, AllProfiles)
  lazy val ConfiguredResultHidden: SbtOutputVerificationSelection = selection(ConfiguredHiddenScenario, AllProfiles)
  lazy val CustomConfiguredResultHidden: SbtOutputVerificationSelection =
    selection(CustomConfiguredScenario, AllProfiles)
  lazy val CustomTeamCityResultHidden: SbtOutputVerificationSelection =
    selection(CustomTeamCityScenario, Jdk17Profiles)
  lazy val TestOnlyConfiguredHidden: SbtOutputVerificationSelection =
    selection(TestOnlyHiddenScenario, Jdk17Profiles)
  lazy val TestQuickConfiguredHidden: SbtOutputVerificationSelection =
    selection(TestQuickHiddenScenario, Jdk17Profiles)
  lazy val IntegrationTestQuickConfiguredHidden: SbtOutputVerificationSelection =
    selection(IntegrationHiddenScenario, Jdk17Profiles)
  lazy val TestFullConfiguredHidden: SbtOutputVerificationSelection =
    selection(TestFullHiddenScenario, Vector(Sbt2Jdk17))

  private enum ResultBehavior {
    case TeamCityHidden
    case ConfiguredWithTaskOutput
    case ConfiguredHidden
    case CustomConfigured
    case CustomTeamCity
  }

  private enum SuiteKind {
    case Main, Integration, Custom
  }

  private final case class Profile(id: String, isSbt2: Boolean, isJdk17: Boolean)

  private final case class Scenario(
    id: String,
    behavior: ResultBehavior,
    suiteKind: SuiteKind,
    executionTask: Profile => String,
    resultFlowConfiguration: Profile => String,
    supports: Profile => Boolean = _ => true
  )

  private final case class SuiteSpec(
    name: String,
    failedTest: String,
    passingTest: Option[String],
    failureMessage: String,
    userFrame: String
  )

  private final case class TestPart(
    events: Vector[ExpectedSemanticEvent],
    lifecycle: SemanticLifecycleRule,
    start: SemanticEventId,
    finish: SemanticEventId
  )

  private final case class CompilationPart(
    events: Vector[ExpectedSemanticEvent],
    edges: Set[HappensBefore],
    lifecycles: Vector[SemanticLifecycleRule],
    optionalGroups: Vector[OptionalSemanticEventGroup],
    buildId: SemanticBindingKey,
    finish: SemanticEventId
  )

  private val MaximumJUnitFrameworkFrames = 128
  private val Sbt1_4 = Profile("sbt-1.4-jdk8", isSbt2 = false, isJdk17 = false)
  private val Sbt1Jdk8 = Profile("sbt-1-jdk8", isSbt2 = false, isJdk17 = false)
  private val Sbt1Jdk17 = Profile("sbt-1-jdk17", isSbt2 = false, isJdk17 = true)
  private val Sbt2Jdk17 = Profile("sbt-2-jdk17", isSbt2 = true, isJdk17 = true)
  private val AllProfiles = Vector(Sbt1_4, Sbt1Jdk8, Sbt1Jdk17, Sbt2Jdk17)
  private val Jdk17Profiles = Vector(Sbt1Jdk17, Sbt2Jdk17)
  private val ProfilesById = AllProfiles.map(profile => profile.id -> profile).toMap

  private val MainSuite = SuiteSpec(
    name = "thisis.a.test.ATest",
    failedTest = "thisis.a.test.ATest.testMeToo",
    passingTest = Some("thisis.a.test.ATest.testMe"),
    failureMessage = "comparing unequal ints is WRONG expected:<3> but was:<1>",
    userFrame = "\tat thisis.a.test.ATest.testMeToo(ATest.scala:15)"
  )
  private val IntegrationSuite = SuiteSpec(
    name = "thisis.a.test.IntegrationATest",
    failedTest = "thisis.a.test.IntegrationATest.testFails",
    passingTest = Some("thisis.a.test.IntegrationATest.testPasses"),
    failureMessage = "integration test failure expected:<3> but was:<1>",
    userFrame = "\tat thisis.a.test.IntegrationATest.testFails(IntegrationATest.scala:11)"
  )
  private val CustomSuite = SuiteSpec(
    name = "example.CustomResultLoggerTest",
    failedTest = "example.CustomResultLoggerTest.failsButConfiguredResultLoggerDoesNotThrow",
    passingTest = None,
    failureMessage = "intentional failure expected:<2> but was:<1>",
    userFrame =
      "\tat example.CustomResultLoggerTest.failsButConfiguredResultLoggerDoesNotThrow(CustomResultLoggerTest.scala:8)"
  )

  private val DefaultExecutionTask: Profile => String = profile => if (profile.isSbt2) "testQuick" else "test"
  private val TestResultFlow: Profile => String = _ => "test"
  private val CompileResultFlow: Profile => String = _ => "compile"

  private val Scenarios = Vector(
    Scenario(TeamCityHiddenScenario, ResultBehavior.TeamCityHidden, SuiteKind.Main,
      DefaultExecutionTask, TestResultFlow),
    Scenario(ConfiguredTaskOutputScenario, ResultBehavior.ConfiguredWithTaskOutput, SuiteKind.Main,
      DefaultExecutionTask, TestResultFlow),
    Scenario(ConfiguredHiddenScenario, ResultBehavior.ConfiguredHidden, SuiteKind.Main,
      DefaultExecutionTask, profile => if (profile.isSbt2) "compile" else "test"),
    Scenario(CustomConfiguredScenario, ResultBehavior.CustomConfigured, SuiteKind.Custom,
      DefaultExecutionTask, TestResultFlow),
    Scenario(CustomTeamCityScenario, ResultBehavior.CustomTeamCity, SuiteKind.Custom,
      DefaultExecutionTask, TestResultFlow, supports = _.isJdk17),
    Scenario(TestOnlyHiddenScenario, ResultBehavior.ConfiguredHidden, SuiteKind.Main,
      profile => if (profile.isSbt2) "testSelected" else "testOnly", CompileResultFlow,
      supports = _.isJdk17),
    Scenario(TestQuickHiddenScenario, ResultBehavior.ConfiguredHidden, SuiteKind.Main,
      _ => "testQuick", CompileResultFlow, supports = _.isJdk17),
    Scenario(IntegrationHiddenScenario, ResultBehavior.ConfiguredHidden, SuiteKind.Integration,
      _ => "testQuick", CompileResultFlow, supports = _.isJdk17),
    Scenario(TestFullHiddenScenario, ResultBehavior.ConfiguredHidden, SuiteKind.Main,
      _ => "testFull", TestResultFlow, supports = _.isSbt2)
  )
  private val ScenariosById = Scenarios.map(scenario => scenario.id -> scenario).toMap

  private[logger] def semanticContractFor(
    scenarioId: String,
    runtimeProfile: String
  ): SbtSemanticContract = {
    val profile = ProfilesById.getOrElse(
      runtimeProfile,
      throw new IllegalArgumentException(s"No JUnit result-logger semantic profile for '$runtimeProfile'.")
    )
    val scenario = ScenariosById.getOrElse(
      scenarioId,
      throw new IllegalArgumentException(s"No JUnit result-logger semantic scenario for '$scenarioId'.")
    )
    if (!scenario.supports(profile)) {
      throw new IllegalArgumentException(
        s"JUnit result-logger scenario '$scenarioId' does not support '$runtimeProfile'."
      )
    }
    contract(scenario, profile)
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

  private def contract(scenario: Scenario, profile: Profile): SbtSemanticContract = {
    val suiteSpec = scenario.suiteKind match {
      case SuiteKind.Main => MainSuite
      case SuiteKind.Integration => IntegrationSuite
      case SuiteKind.Custom => CustomSuite
    }
    val compilation = compilationPart(scenario, profile)
    val suiteFlow = SemanticBindingKey.flow(s"${scenario.id}-suite-flow")
    val suiteStart = ExpectedSemanticEvent("suite-start", TestSuiteStarted,
      "name" -> exact(suiteSpec.name))
    val failed = failedTest(suiteSpec, suiteFlow)
    val passing = suiteSpec.passingTest.map(passingTest(_, suiteFlow))
    val childParts = Vector(failed) ++ passing
    val childEvents = childParts.flatMap(_.events)
    val suiteFinish = ExpectedSemanticEvent("suite-finish", TestSuiteFinished,
      "name" -> exact(suiteSpec.name))
    val taskOutput = if (scenario.behavior == ResultBehavior.ConfiguredWithTaskOutput)
      failureTaskOutput(scenario, profile, suiteSpec, compilation.buildId)
    else Vector.empty
    val resultOutput = resultLoggerOutput(scenario, profile, suiteSpec, compilation.buildId)
    val siblingEdges = childParts.sliding(2).collect { case Vector(before, after) =>
      HappensBefore(before.finish, after.start)
    }.toSet
    val taskEdges = if (taskOutput.nonEmpty)
      chainEdges(Vector(suiteStart.id) ++ taskOutput.map(_.id) :+ failed.start)
    else Set.empty[HappensBefore]
    val resultEdges = if (resultOutput.nonEmpty)
      chainEdges(Vector(suiteFinish.id) ++ resultOutput.map(_.id))
    else Set.empty[HappensBefore]

    SbtSemanticContract(
      events = compilation.events ++ Vector(suiteStart) ++ taskOutput ++ childEvents ++
        Vector(suiteFinish) ++ resultOutput,
      happensBefore = compilation.edges ++ siblingEdges ++ taskEdges ++ resultEdges +
        HappensBefore(compilation.finish, suiteStart.id),
      lifecycles = compilation.lifecycles ++ Vector(
        SemanticLifecycleRule.suite(
          "junit-suite",
          suiteStart.id.value,
          childEvents.map(_.id.value),
          suiteFinish.id.value,
          suiteFlow
        )
      ) ++ childParts.map(_.lifecycle),
      optionalGroups = compilation.optionalGroups,
      plainOutput = PlainOutputContract.Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
    )
  }

  private def compilationPart(scenario: Scenario, profile: Profile): CompilationPart =
    if (scenario.suiteKind == SuiteKind.Integration) integrationCompilation(scenario, profile)
    else testCompilation(scenario, profile)

  private def testCompilation(scenario: Scenario, profile: Profile): CompilationPart = {
    val buildId = SemanticBindingKey.buildId(s"${scenario.id}-build")
    val outputBase = SemanticBindingKey.path(s"${scenario.id}-output-base")
    val ownership = embedded("", buildId, ":test:compiler")
    val compiler = s"Scala compiler in Test [${scenario.id}]"
    val start = ExpectedSemanticEvent("compile-start", CompilationStarted,
      "compiler" -> exact(compiler))
    val suffix = if (!profile.isSbt2) "/target/scala-2.13/test-classes ..."
    else {
      val scalaVersion = if (scenario.suiteKind == SuiteKind.Custom) "2.13.18" else "2.12.7"
      s"/target/out/jvm/scala-$scalaVersion/${scenario.id}/test-classes ..."
    }
    val info = ExpectedSemanticEvent("compile-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded("[info] compiling 1 Scala source to ", outputBase, suffix))
    val done = ExpectedSemanticEvent("compile-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> exact("[info] done compiling"))
    val finish = ExpectedSemanticEvent("compile-finish", CompilationFinished,
      "compiler" -> exact(compiler))
    val bridge = compilerBridge("compile", ownership)

    CompilationPart(
      events = Vector(start, info, done, finish),
      edges = Set(HappensBefore(info.id, done.id)),
      lifecycles = Vector(SemanticLifecycleRule.compilation(
        "test-compilation",
        start.id.value,
        Seq(info.id.value, done.id.value),
        finish.id.value,
        ownership
      )),
      optionalGroups = Vector(OptionalSemanticEventGroup(
        "test-compiler-bridge",
        bridge,
        Set(
          HappensBefore(info.id, bridge.head.id),
          HappensBefore(bridge.head.id, bridge.last.id),
          HappensBefore(bridge.last.id, done.id)
        ),
        Some(ownership)
      )),
      buildId = buildId,
      finish = finish.id
    )
  }

  private def integrationCompilation(scenario: Scenario, profile: Profile): CompilationPart = {
    val buildId = SemanticBindingKey.buildId(s"${scenario.id}-build")
    val outputBase = SemanticBindingKey.path(s"${scenario.id}-output-base")
    val suffix = if (profile.isSbt2) "/target/out/jvm/scala-2.12.7/root/it-classes ..."
    else "/target/scala-2.13/it-classes ..."
    val info = ExpectedSemanticEvent("compile-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded("[info] compiling 1 Scala source to ", outputBase, suffix))
    val done = ExpectedSemanticEvent("compile-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> exact("[info] done compiling"))
    val bridge = integrationCompilerBridge(profile)

    CompilationPart(
      events = Vector(info, done),
      edges = Set(HappensBefore(info.id, done.id)),
      lifecycles = Vector.empty,
      optionalGroups = Vector(OptionalSemanticEventGroup(
        "integration-compiler-bridge",
        bridge,
        Set(
          HappensBefore(info.id, bridge.head.id),
          HappensBefore(bridge.head.id, bridge.last.id),
          HappensBefore(bridge.last.id, done.id)
        )
      )),
      buildId = buildId,
      finish = done.id
    )
  }

  private def compilerBridge(
    idPrefix: String,
    ownership: SemanticValuePattern
  ): Vector[ExpectedSemanticEvent] = {
    val attributes = (id: String, text: SemanticValuePattern) => {
      val base = Vector("status" -> exact("NORMAL"), "text" -> text)
      val withOwnership = base.patch(1, Vector("flowId" -> ownership), 0)
      ExpectedSemanticEvent(id, BuildLogMessage, withOwnership*)
    }
    Vector(
      attributes(s"$idPrefix-bridge-announcement", compilerBridgeAnnouncement),
      attributes(s"$idPrefix-bridge-completion", compilerBridgeCompletion)
    )
  }

  private def integrationCompilerBridge(profile: Profile): Vector[ExpectedSemanticEvent] = {
    val (module, scalaVersion) = if (profile.isSbt2) "compiler-bridge_2.12" -> "2.12.7"
    else "compiler-bridge_2.13" -> "2.13.18"
    val duration = SemanticBindingKey.durationSeconds("integration-bridge-duration")
    Vector(
      ExpectedSemanticEvent("compile-bridge-announcement", BuildLogMessage,
        "status" -> exact("NORMAL"),
        "text" -> exact(
          s"[info] Non-compiled module '$module' for Scala $scalaVersion. Compiling..."
        )),
      ExpectedSemanticEvent("compile-bridge-completion", BuildLogMessage,
        "status" -> exact("NORMAL"),
        "text" -> embedded("[info]   Compilation completed in ", duration, "s."))
    )
  }

  private def failureTaskOutput(
    scenario: Scenario,
    profile: Profile,
    suite: SuiteSpec,
    buildId: SemanticBindingKey
  ): Vector[ExpectedSemanticEvent] = {
    val task = scenario.executionTask(profile)
    val ownership = buildFlow(buildId, "test", task)
    val duration = SemanticBindingKey.durationSeconds(s"${scenario.id}-task-duration")
    val renderedFailure =
      if (profile.isSbt2) suite.failureMessage
      else s"java.lang.AssertionError: ${suite.failureMessage}"
    val summary = errorMessage(
      "task-failure-summary",
      ownership,
      embedded(s"[error] Test ${suite.failedTest} failed: $renderedFailure, took ", duration, " sec")
    )
    if (profile.isSbt2) Vector(summary)
    else {
      val userFrame = errorMessage(
        "task-user-frame",
        ownership,
        exact(s"[error]     at ${suite.userFrame.stripPrefix("\tat ")}")
      )
      val reflection = if (profile.isJdk17) Vector(
        reflectionFrame("task-reflection-native0", ownership, NativeAccessorInvoke0),
        reflectionFrame("task-reflection-native", ownership, NativeAccessorInvoke),
        reflectionFrame("task-reflection-delegating", ownership, DelegatingAccessorInvoke),
        reflectionFrame("task-reflection-method", ownership, MethodInvoke)
      ) else Vector.empty
      Vector(summary, userFrame) ++ reflection ++ Vector(errorMessage(
        "task-stack-ellipsis", ownership, exact("[error]     ...")
      ))
    }
  }

  private def resultLoggerOutput(
    scenario: Scenario,
    profile: Profile,
    suite: SuiteSpec,
    buildId: SemanticBindingKey
  ): Vector[ExpectedSemanticEvent] = scenario.behavior match {
    case ResultBehavior.ConfiguredWithTaskOutput =>
      configuredFailureSummary(scenario, profile, suite, buildId) :+ errorMessage(
        "task-terminal-failure",
        buildFlow(buildId, "test", scenario.executionTask(profile)),
        exact(s"[error] (Test / ${scenario.executionTask(profile)}) " +
          "sbt.TestsFailedException: Tests unsuccessful")
      )
    case ResultBehavior.ConfiguredHidden => configuredFailureSummary(scenario, profile, suite, buildId)
    case ResultBehavior.CustomConfigured => Vector(ExpectedSemanticEvent(
      "custom-result-marker",
      BuildLogMessage,
      "status" -> exact("NORMAL"),
      "flowId" -> buildFlow(buildId, "test", scenario.executionTask(profile)),
      "text" -> exact("[info] CUSTOM_TEST_RESULT_LOGGER")
    ))
    case ResultBehavior.TeamCityHidden | ResultBehavior.CustomTeamCity => Vector.empty
  }

  private def configuredFailureSummary(
    scenario: Scenario,
    profile: Profile,
    suite: SuiteSpec,
    buildId: SemanticBindingKey
  ): Vector[ExpectedSemanticEvent] = {
    val ownership = buildFlow(
      buildId,
      scenario.resultFlowConfiguration(profile),
      scenario.executionTask(profile)
    )
    Vector(
      errorMessage("configured-result-count", ownership,
        exact("[error] Failed: Total 2, Failed 1, Errors 0, Passed 1")),
      errorMessage("configured-result-heading", ownership, exact("[error] Failed tests:")),
      errorMessage("configured-result-suite", ownership, exact(s"[error] \t${suite.name}"))
    )
  }

  private def reflectionFrame(
    id: String,
    ownership: SemanticValuePattern,
    role: JdkMethodReflectionFrameRole
  ): ExpectedSemanticEvent = errorMessage(id, ownership, jdkMethodReflectionFrame(role))

  private def errorMessage(
    id: String,
    ownership: SemanticValuePattern,
    text: SemanticValuePattern
  ): ExpectedSemanticEvent = ExpectedSemanticEvent(id, BuildLogMessage,
    "status" -> exact("ERROR"),
    "flowId" -> ownership,
    "text" -> text)

  private def failedTest(spec: SuiteSpec, flow: SemanticBindingKey): TestPart = {
    val start = ExpectedSemanticEvent("failed-test-start", TestStarted,
      "name" -> exact(spec.failedTest),
      "captureStandardOutput" -> exact("true"))
    val failure = ExpectedSemanticEvent("failed-test-outcome", TestFailed,
      "name" -> exact(spec.failedTest),
      "details" -> userFailure(
        s"java.lang.AssertionError: ${spec.failureMessage}",
        Seq(spec.userFrame),
        RecognizedTestFramework.JUnit,
        maximumFrameworkFrames = MaximumJUnitFrameworkFrames
      ))
    val finish = ExpectedSemanticEvent("failed-test-finish", TestFinished,
      "name" -> exact(spec.failedTest),
      "duration" -> unsignedDuration)
    val events = Vector(start, failure, finish)
    TestPart(
      events,
      SemanticLifecycleRule.test(
        "failed-junit-test",
        start.id.value,
        Seq(failure.id.value),
        finish.id.value,
        flow
      ),
      start.id,
      finish.id
    )
  }

  private def passingTest(name: String, flow: SemanticBindingKey): TestPart = {
    val start = ExpectedSemanticEvent("passing-test-start", TestStarted,
      "name" -> exact(name),
      "captureStandardOutput" -> exact("true"))
    val finish = ExpectedSemanticEvent("passing-test-finish", TestFinished,
      "name" -> exact(name),
      "duration" -> unsignedDuration)
    TestPart(
      Vector(start, finish),
      SemanticLifecycleRule.test(
        "passing-junit-test",
        start.id.value,
        Seq.empty,
        finish.id.value,
        flow
      ),
      start.id,
      finish.id
    )
  }

  private def buildFlow(
    buildId: SemanticBindingKey,
    configuration: String,
    task: String
  ): SemanticValuePattern = embedded("", buildId, s":$configuration:general:$task")

  private def chainEdges(ids: Vector[SemanticEventId]): Set[HappensBefore] =
    ids.sliding(2).collect { case Vector(before, after) => HappensBefore(before, after) }.toSet
}
