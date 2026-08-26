package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtJUnitTaskSemanticContracts {
  import JdkMethodReflectionFrameRole.*
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  private val PassScenario = "junit-pass-and-failure"
  private val TestQuickScenario = "junit-test-quick"
  private val TestOnlyScenario = "junit-test-only"
  private val TestFullScenario = "junit-test-full"
  private val IntegrationQuickScenario = "integration-test-quick"

  lazy val PassAndFailure: SbtOutputVerificationSelection = selection(PassScenario, AllProfiles)
  lazy val TestQuick: SbtOutputVerificationSelection = selection(TestQuickScenario, AllProfiles)
  lazy val TestOnly: SbtOutputVerificationSelection = selection(TestOnlyScenario, AllProfiles)
  lazy val TestFull: SbtOutputVerificationSelection = selection(TestFullScenario, Vector(Sbt2Jdk17))
  lazy val IntegrationTestQuick: SbtOutputVerificationSelection =
    selection(IntegrationQuickScenario, Vector(Sbt1Jdk8, Sbt1Jdk17, Sbt2Jdk17))

  private final case class Profile(id: String, isSbt2: Boolean, isJdk17: Boolean)

  private final case class Scenario(
    id: String,
    suiteName: String,
    failedTestName: String,
    passingTestName: String,
    failureMessage: String,
    userFrame: String,
    flowConfiguration: String,
    displayConfiguration: String,
    sbt1OutputTask: String,
    sbt1TerminalTask: String,
    sbt2OutputTask: String,
    sbt2TerminalTask: String,
    supports: Profile => Boolean = _ => true
  ) {
    def outputTask(profile: Profile): String = if (profile.isSbt2) sbt2OutputTask else sbt1OutputTask
    def terminalTask(profile: Profile): String = if (profile.isSbt2) sbt2TerminalTask else sbt1TerminalTask
  }

  private final case class TestPart(
    events: Vector[ExpectedSemanticEvent],
    lifecycle: SemanticLifecycleRule,
    start: SemanticEventId,
    finish: SemanticEventId
  )

  private val MaximumJUnitFrameworkFrames = 128

  private val Sbt1_4 = Profile("sbt-1.4-jdk8", isSbt2 = false, isJdk17 = false)
  private val Sbt1Jdk8 = Profile("sbt-1-jdk8", isSbt2 = false, isJdk17 = false)
  private val Sbt1Jdk17 = Profile("sbt-1-jdk17", isSbt2 = false, isJdk17 = true)
  private val Sbt2Jdk17 = Profile("sbt-2-jdk17", isSbt2 = true, isJdk17 = true)
  private val AllProfiles = Vector(Sbt1_4, Sbt1Jdk8, Sbt1Jdk17, Sbt2Jdk17)
  private val ProfilesById = AllProfiles.map(profile => profile.id -> profile).toMap

  private val MainSuite = "thisis.a.test.ATest"
  private val MainFailure = "comparing unequal ints is WRONG expected:<3> but was:<1>"
  private val IntegrationSuite = "thisis.a.test.IntegrationATest"
  private val IntegrationFailure = "integration test failure expected:<3> but was:<1>"

  private val Scenarios = Vector(
    mainScenario(PassScenario, "test", "test", "testQuick", "testQuick"),
    mainScenario(TestQuickScenario, "testQuick", "testQuick", "testQuick", "testQuick"),
    mainScenario(TestOnlyScenario, "testOnly", "testOnly", "testSelected", "testSelected"),
    mainScenario(
      TestFullScenario,
      "testFull",
      "testFull",
      "test",
      "testFull",
      supports = _.isSbt2
    ),
    Scenario(
      id = IntegrationQuickScenario,
      suiteName = IntegrationSuite,
      failedTestName = s"$IntegrationSuite.testFails",
      passingTestName = s"$IntegrationSuite.testPasses",
      failureMessage = IntegrationFailure,
      userFrame = "\tat thisis.a.test.IntegrationATest.testFails(IntegrationATest.scala:11)",
      flowConfiguration = "it",
      displayConfiguration = "IntegrationTest",
      sbt1OutputTask = "testQuick",
      sbt1TerminalTask = "testQuick",
      sbt2OutputTask = "testQuick",
      sbt2TerminalTask = "testQuick",
      supports = _.id != Sbt1_4.id
    )
  )
  private val ScenariosById = Scenarios.map(scenario => scenario.id -> scenario).toMap

  private def mainScenario(
    id: String,
    sbt1OutputTask: String,
    sbt1TerminalTask: String,
    sbt2OutputTask: String,
    sbt2TerminalTask: String,
    supports: Profile => Boolean = _ => true
  ): Scenario = Scenario(
    id = id,
    suiteName = MainSuite,
    failedTestName = s"$MainSuite.testMeToo",
    passingTestName = s"$MainSuite.testMe",
    failureMessage = MainFailure,
    userFrame = "\tat thisis.a.test.ATest.testMeToo(ATest.scala:15)",
    flowConfiguration = "test",
    displayConfiguration = "Test",
    sbt1OutputTask = sbt1OutputTask,
    sbt1TerminalTask = sbt1TerminalTask,
    sbt2OutputTask = sbt2OutputTask,
    sbt2TerminalTask = sbt2TerminalTask,
    supports = supports
  )

  private[logger] def semanticContractFor(
    scenarioId: String,
    runtimeProfile: String
  ): SbtSemanticContract = {
    val profile = ProfilesById.getOrElse(
      runtimeProfile,
      throw new IllegalArgumentException(s"No JUnit task semantic profile for '$runtimeProfile'.")
    )
    val scenario = ScenariosById.getOrElse(
      scenarioId,
      throw new IllegalArgumentException(s"No JUnit task semantic scenario for '$scenarioId'.")
    )
    if (!scenario.supports(profile)) {
      throw new IllegalArgumentException(s"JUnit task scenario '$scenarioId' does not support '$runtimeProfile'.")
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
    val buildId = SemanticBindingKey.buildId(s"${scenario.id}-build")
    val suiteFlow = SemanticBindingKey.flow(s"${scenario.id}-suite-flow")
    val taskDuration = SemanticBindingKey.durationSeconds(s"${scenario.id}-task-duration")
    val outputFlow = buildFlow(buildId, scenario.flowConfiguration, scenario.outputTask(profile))
    val terminalFlow = buildFlow(buildId, scenario.flowConfiguration, scenario.terminalTask(profile))

    val suiteStart = ExpectedSemanticEvent("suite-start", TestSuiteStarted,
      "name" -> exact(scenario.suiteName))
    val taskOutput = failureTaskOutput(scenario, profile, outputFlow, taskDuration)
    val failed = failedTest(scenario, suiteFlow)
    val passing = passingTest(scenario, suiteFlow)
    val suiteFinish = ExpectedSemanticEvent("suite-finish", TestSuiteFinished,
      "name" -> exact(scenario.suiteName))
    val terminalFailure = ExpectedSemanticEvent("task-terminal-failure", BuildLogMessage,
      "status" -> exact("ERROR"),
      "flowId" -> terminalFlow,
      "text" -> exact(
        s"[error] (${scenario.displayConfiguration} / ${scenario.terminalTask(profile)}) " +
          "sbt.TestsFailedException: Tests unsuccessful"
      ))
    val childEvents = failed.events ++ passing.events

    SbtSemanticContract(
      events = Vector(suiteStart) ++ taskOutput ++ childEvents ++ Vector(suiteFinish, terminalFailure),
      happensBefore = chainEdges(Vector(suiteStart.id) ++ taskOutput.map(_.id) :+ failed.start) ++ Set(
        HappensBefore(failed.finish, passing.start),
        HappensBefore(suiteFinish.id, terminalFailure.id)
      ),
      lifecycles = Vector(
        SemanticLifecycleRule.suite(
          "junit-suite",
          suiteStart.id.value,
          childEvents.map(_.id.value),
          suiteFinish.id.value,
          suiteFlow
        ),
        failed.lifecycle,
        passing.lifecycle
      ),
      plainOutput = if (profile.isSbt2)
        PlainOutputContract.Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
      else PlainOutputContract.RejectAll
    )
  }

  private def failureTaskOutput(
    scenario: Scenario,
    profile: Profile,
    ownership: SemanticValuePattern,
    duration: SemanticBindingKey
  ): Vector[ExpectedSemanticEvent] = {
    val renderedFailure =
      if (profile.isSbt2) scenario.failureMessage
      else s"java.lang.AssertionError: ${scenario.failureMessage}"
    val summary = message(
      "task-failure-summary",
      ownership,
      embedded(
        s"[error] Test ${scenario.failedTestName} failed: $renderedFailure, took ",
        duration,
        " sec"
      )
    )
    if (profile.isSbt2) Vector(summary)
    else {
      val userFrame = message(
        "task-user-frame",
        ownership,
        exact(s"[error]     at ${scenario.userFrame.stripPrefix("\tat ")}")
      )
      val reflection = if (profile.isJdk17) Vector(
        reflectionFrame("task-reflection-native0", ownership, NativeAccessorInvoke0),
        reflectionFrame("task-reflection-native", ownership, NativeAccessorInvoke),
        reflectionFrame("task-reflection-delegating", ownership, DelegatingAccessorInvoke),
        reflectionFrame("task-reflection-method", ownership, MethodInvoke)
      ) else Vector.empty
      Vector(summary, userFrame) ++ reflection ++ Vector(message(
        "task-stack-ellipsis",
        ownership,
        exact("[error]     ...")
      ))
    }
  }

  private def reflectionFrame(
    id: String,
    ownership: SemanticValuePattern,
    role: JdkMethodReflectionFrameRole
  ): ExpectedSemanticEvent = message(id, ownership, jdkMethodReflectionFrame(role))

  private def message(
    id: String,
    ownership: SemanticValuePattern,
    text: SemanticValuePattern
  ): ExpectedSemanticEvent = ExpectedSemanticEvent(id, BuildLogMessage,
    "status" -> exact("ERROR"),
    "flowId" -> ownership,
    "text" -> text)

  private def failedTest(scenario: Scenario, flow: SemanticBindingKey): TestPart = {
    val start = ExpectedSemanticEvent("failed-test-start", TestStarted,
      "name" -> exact(scenario.failedTestName),
      "captureStandardOutput" -> exact("true"))
    val failure = ExpectedSemanticEvent("failed-test-outcome", TestFailed,
      "name" -> exact(scenario.failedTestName),
      "details" -> userFailure(
        s"java.lang.AssertionError: ${scenario.failureMessage}",
        Seq(scenario.userFrame),
        RecognizedTestFramework.JUnit,
        maximumFrameworkFrames = MaximumJUnitFrameworkFrames
      ))
    val finish = ExpectedSemanticEvent("failed-test-finish", TestFinished,
      "name" -> exact(scenario.failedTestName),
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

  private def passingTest(scenario: Scenario, flow: SemanticBindingKey): TestPart = {
    val start = ExpectedSemanticEvent("passing-test-start", TestStarted,
      "name" -> exact(scenario.passingTestName),
      "captureStandardOutput" -> exact("true"))
    val finish = ExpectedSemanticEvent("passing-test-finish", TestFinished,
      "name" -> exact(scenario.passingTestName),
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
