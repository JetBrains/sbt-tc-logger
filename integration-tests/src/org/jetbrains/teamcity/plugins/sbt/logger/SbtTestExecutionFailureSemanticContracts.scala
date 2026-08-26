package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtTestExecutionFailureSemanticContracts {
  import JdkMethodReflectionFrameRole.*
  import ObservedServiceMessageKind.*
  import PlainOutputContract.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  private val Sbt1Profile = "sbt-1-jdk17"
  private val Sbt2Profile = "sbt-2-jdk17"
  private val Profiles = Vector(Sbt1Profile, Sbt2Profile)
  private val SuiteName = "FailedTest"
  private val TestName = s"$SuiteName.fails"
  private val FailureMessage = "#9 direct task-result fixture"
  private val UserFrame = "\tat FailedTest.fails(FailedTest.scala:5)"

  lazy val IncompleteResult: SbtOutputVerificationSelection =
    selection(profile => incompleteResultContract(isSbt2(profile)))
  lazy val ForkedChildExit: SbtOutputVerificationSelection =
    selection(profile => forkedChildExitContract(isSbt2(profile)))

  private[logger] def incompleteResultContractFor(runtimeProfile: String): SbtSemanticContract =
    incompleteResultContract(isSbt2(knownProfile(runtimeProfile, "incomplete test result")))

  private[logger] def forkedChildExitContractFor(runtimeProfile: String): SbtSemanticContract =
    forkedChildExitContract(isSbt2(knownProfile(runtimeProfile, "forked child exit")))

  private def selection(
    contract: String => SbtSemanticContract
  ): SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = ExactTranscript,
    runtimeProfileOverrides = Profiles.map(profile => profile -> Semantic(contract(profile))).toMap
  )

  private def knownProfile(runtimeProfile: String, scenario: String): String = {
    if (!Profiles.contains(runtimeProfile)) {
      throw new IllegalArgumentException(s"No $scenario semantic profile for '$runtimeProfile'.")
    }
    runtimeProfile
  }

  private def isSbt2(runtimeProfile: String): Boolean = runtimeProfile == Sbt2Profile

  private def incompleteResultContract(sbt2: Boolean): SbtSemanticContract = {
    val scenarioId = "test-result-is-incomplete"
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val suiteFlow = SemanticBindingKey.flow(s"$scenarioId-suite-flow")
    val taskDuration = SemanticBindingKey.durationSeconds(s"$scenarioId-task-duration")
    val task = if (sbt2) "testQuick" else "test"
    val outputFlow = embedded("", buildId, s":test:general:$task")
    val suiteStart = ExpectedSemanticEvent("suite-start", TestSuiteStarted,
      "name" -> exact(SuiteName))
    val summary = ExpectedSemanticEvent("task-failure-summary", BuildLogMessage,
      "status" -> exact("ERROR"),
      "flowId" -> outputFlow,
      "text" -> embedded(
        s"[error] Test $TestName failed: ${if (sbt2) "" else "java.lang.AssertionError: "}$FailureMessage, took ",
        taskDuration,
        " sec"
      ))
    val sbt1Stack = if (sbt2) Vector.empty else Vector(
      errorMessage("task-user-frame", outputFlow,
        exact(s"[error]     at ${UserFrame.stripPrefix("\tat ")}")),
      errorMessage("task-reflection-native0", outputFlow, jdkMethodReflectionFrame(NativeAccessorInvoke0)),
      errorMessage("task-reflection-native", outputFlow, jdkMethodReflectionFrame(NativeAccessorInvoke)),
      errorMessage("task-reflection-delegating", outputFlow, jdkMethodReflectionFrame(DelegatingAccessorInvoke)),
      errorMessage("task-reflection-method", outputFlow, jdkMethodReflectionFrame(MethodInvoke)),
      errorMessage("task-stack-ellipsis", outputFlow, exact("[error]     ..."))
    )
    val testStart = ExpectedSemanticEvent("test-start", TestStarted,
      "name" -> exact(TestName),
      "captureStandardOutput" -> exact("true"))
    val testFailure = ExpectedSemanticEvent("test-failure", TestFailed,
      "name" -> exact(TestName),
      "details" -> userFailure(
        s"java.lang.AssertionError: $FailureMessage",
        Seq(UserFrame),
        RecognizedTestFramework.JUnit,
        maximumFrameworkFrames = 64
      ))
    val testFinish = ExpectedSemanticEvent("test-finish", TestFinished,
      "name" -> exact(TestName),
      "duration" -> unsignedDuration)
    val suiteFinish = ExpectedSemanticEvent("suite-finish", TestSuiteFinished,
      "name" -> exact(SuiteName))
    val taskOutput = Vector(summary) ++ sbt1Stack
    val testEvents = Vector(testStart, testFailure, testFinish)
    val all = Vector(suiteStart) ++ taskOutput ++ testEvents ++ Vector(suiteFinish)

    SbtSemanticContract(
      events = all,
      happensBefore = chainEdges(all.map(_.id)),
      lifecycles = Vector(
        SemanticLifecycleRule.suite(
          "failed-suite",
          suiteStart.id.value,
          testEvents.map(_.id.value),
          suiteFinish.id.value,
          suiteFlow
        ),
        SemanticLifecycleRule.test(
          "failed-test",
          testStart.id.value,
          Seq(testFailure.id.value),
          testFinish.id.value,
          suiteFlow
        )
      ),
      plainOutput = RejectAll
    )
  }

  private def forkedChildExitContract(sbt2: Boolean): SbtSemanticContract = {
    val scenarioId = "forked-test-child-exit"
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val task = if (sbt2) "testQuick" else "test"
    val flow = embedded("", buildId, s":test:general:$task")
    val events = if (sbt2) Vector(
      errorMessage(
        "forked-process-failure",
        flow,
        exact("[error] java.lang.RuntimeException: Forked test process exited with code 255")
      ),
      errorMessage(
        "forked-task-failure",
        flow,
        exact("[error] (Test / testQuick) Forked test process exited with code 255")
      )
    ) else Vector(
      errorMessage(
        "forked-task-failure",
        flow,
        exact("[error] (Test / test) sbt.TestsFailedException: Tests unsuccessful")
      )
    )

    SbtSemanticContract(
      events = events,
      happensBefore = chainEdges(events.map(_.id)),
      plainOutput = if (sbt2) Patterns(Vector(AfterServiceMessages(SbtTaskSummary))) else RejectAll
    )
  }

  private def errorMessage(
    id: String,
    flow: SemanticValuePattern,
    text: SemanticValuePattern
  ): ExpectedSemanticEvent = ExpectedSemanticEvent(id, BuildLogMessage,
    "status" -> exact("ERROR"),
    "flowId" -> flow,
    "text" -> text)

  private def chainEdges(ids: Vector[SemanticEventId]): Set[HappensBefore] =
    ids.sliding(2).collect { case Vector(before, after) => HappensBefore(before, after) }.toSet
}
