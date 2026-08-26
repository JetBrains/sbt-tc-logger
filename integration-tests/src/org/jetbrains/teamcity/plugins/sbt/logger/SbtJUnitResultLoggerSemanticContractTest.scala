package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtJUnitResultLoggerSemanticContractTest {
  import ProcessResultContract.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private enum Behavior {
    case TeamCityHidden, ConfiguredTaskOutput, ConfiguredHidden, CustomConfigured, CustomTeamCity
  }

  private enum SuiteKind {
    case Main, Integration, Custom
  }

  private final case class Scenario(
    id: String,
    selection: SbtOutputVerificationSelection,
    profiles: Vector[String],
    behavior: Behavior,
    suiteKind: SuiteKind,
    task: String => String,
    resultConfiguration: String => String
  )

  private final case class SuiteSpec(
    name: String,
    failedTest: String,
    passingTest: Option[String],
    failureMessage: String,
    userFrame: String
  )

  private val AllProfiles = Vector("sbt-1.4-jdk8", "sbt-1-jdk8", "sbt-1-jdk17", "sbt-2-jdk17")
  private val Jdk17Profiles = Vector("sbt-1-jdk17", "sbt-2-jdk17")
  private val FutureProfile = "sbt-3-jdk21"
  private val TaskSummary = "[error] elapsed time: 1 s, cache 20%, 2 disk cache hits"

  private val MainSuite = SuiteSpec(
    "thisis.a.test.ATest",
    "thisis.a.test.ATest.testMeToo",
    Some("thisis.a.test.ATest.testMe"),
    "comparing unequal ints is WRONG expected:<3> but was:<1>",
    "\tat thisis.a.test.ATest.testMeToo(ATest.scala:15)"
  )
  private val IntegrationSuite = SuiteSpec(
    "thisis.a.test.IntegrationATest",
    "thisis.a.test.IntegrationATest.testFails",
    Some("thisis.a.test.IntegrationATest.testPasses"),
    "integration test failure expected:<3> but was:<1>",
    "\tat thisis.a.test.IntegrationATest.testFails(IntegrationATest.scala:11)"
  )
  private val CustomSuite = SuiteSpec(
    "example.CustomResultLoggerTest",
    "example.CustomResultLoggerTest.failsButConfiguredResultLoggerDoesNotThrow",
    None,
    "intentional failure expected:<2> but was:<1>",
    "\tat example.CustomResultLoggerTest.failsButConfiguredResultLoggerDoesNotThrow" +
      "(CustomResultLoggerTest.scala:8)"
  )

  private val DefaultTask: String => String = profile => if (isSbt2(profile)) "testQuick" else "test"
  private val Scenarios = Vector(
    Scenario(
      "junit-teamcity-result-no-task-output",
      SbtJUnitResultLoggerSemanticContracts.TeamCityResultHidden,
      AllProfiles,
      Behavior.TeamCityHidden,
      SuiteKind.Main,
      DefaultTask,
      _ => "test"
    ),
    Scenario(
      "junit-configured-result-task-output",
      SbtJUnitResultLoggerSemanticContracts.ConfiguredResultWithTaskOutput,
      AllProfiles,
      Behavior.ConfiguredTaskOutput,
      SuiteKind.Main,
      DefaultTask,
      _ => "test"
    ),
    Scenario(
      "junit-configured-result-no-task-output",
      SbtJUnitResultLoggerSemanticContracts.ConfiguredResultHidden,
      AllProfiles,
      Behavior.ConfiguredHidden,
      SuiteKind.Main,
      DefaultTask,
      profile => if (isSbt2(profile)) "compile" else "test"
    ),
    Scenario(
      "custom-result-logger-no-task-output",
      SbtJUnitResultLoggerSemanticContracts.CustomConfiguredResultHidden,
      AllProfiles,
      Behavior.CustomConfigured,
      SuiteKind.Custom,
      DefaultTask,
      _ => "test"
    ),
    Scenario(
      "custom-result-logger-teamcity-hidden",
      SbtJUnitResultLoggerSemanticContracts.CustomTeamCityResultHidden,
      Jdk17Profiles,
      Behavior.CustomTeamCity,
      SuiteKind.Custom,
      DefaultTask,
      _ => "test"
    ),
    Scenario(
      "junit-test-only-configured-hidden",
      SbtJUnitResultLoggerSemanticContracts.TestOnlyConfiguredHidden,
      Jdk17Profiles,
      Behavior.ConfiguredHidden,
      SuiteKind.Main,
      profile => if (isSbt2(profile)) "testSelected" else "testOnly",
      _ => "compile"
    ),
    Scenario(
      "junit-test-quick-configured-hidden",
      SbtJUnitResultLoggerSemanticContracts.TestQuickConfiguredHidden,
      Jdk17Profiles,
      Behavior.ConfiguredHidden,
      SuiteKind.Main,
      _ => "testQuick",
      _ => "compile"
    ),
    Scenario(
      "integration-test-quick-configured-hidden",
      SbtJUnitResultLoggerSemanticContracts.IntegrationTestQuickConfiguredHidden,
      Jdk17Profiles,
      Behavior.ConfiguredHidden,
      SuiteKind.Integration,
      _ => "testQuick",
      _ => "compile"
    ),
    Scenario(
      "junit-test-full-configured-hidden",
      SbtJUnitResultLoggerSemanticContracts.TestFullConfiguredHidden,
      Vector("sbt-2-jdk17"),
      Behavior.ConfiguredHidden,
      SuiteKind.Main,
      _ => "testFull",
      _ => "test"
    )
  )

  @Test def supportedCoordinatesSelectSemanticAndUnknownCoordinatesRemainExact(): Unit = {
    Scenarios.foreach { scenario =>
      scenario.profiles.foreach(profile => Assert.assertTrue(
        s"Expected semantic ${scenario.id}/$profile",
        scenario.selection.forRuntimeProfile(profile).isInstanceOf[Semantic]
      ))
      Assert.assertEquals(ExactTranscript, scenario.selection.forRuntimeProfile(FutureProfile))
      AllProfiles.filterNot(scenario.profiles.contains).foreach(profile =>
        Assert.assertEquals(ExactTranscript, scenario.selection.forRuntimeProfile(profile)))
    }
  }

  @Test def allTwentyFiveCoordinatesAcceptTheirResultLoggerAndCompilationShape(): Unit = {
    var coordinates = 0
    Scenarios.foreach { scenario =>
      scenario.profiles.foreach { profile =>
        verify(transcript(scenario, profile), scenario.selection.forRuntimeProfile(profile))
        coordinates += 1
      }
    }
    Assert.assertEquals(25, coordinates)

    val bridged = Scenarios.find(_.id == "junit-configured-result-no-task-output").get
    verify(transcript(bridged, "sbt-1-jdk17", includeBridge = true),
      bridged.selection.forRuntimeProfile("sbt-1-jdk17"))
    val integration = Scenarios.find(_.suiteKind == SuiteKind.Integration).get
    verify(transcript(integration, "sbt-2-jdk17", includeBridge = true),
      integration.selection.forRuntimeProfile("sbt-2-jdk17"))
  }

  @Test def outputControlsRequireTheSelectedLoggerMessagesAndRejectHiddenOnes(): Unit = {
    val teamCity = scenario("junit-teamcity-result-no-task-output")
    val teamCityMode = teamCity.selection.forRuntimeProfile("sbt-1-jdk17")
    val inventedTerminal = insertBeforeTaskSummary(
      transcript(teamCity, "sbt-1-jdk17"),
      message("100:test:general:test", "ERROR",
        "[error] (Test / test) sbt.TestsFailedException: Tests unsuccessful")
    )
    assertViolation(collect(inventedTerminal, teamCityMode), SemanticCardinalityFailure)

    val configured = scenario("junit-configured-result-no-task-output")
    val configuredMode = configured.selection.forRuntimeProfile("sbt-2-jdk17")
    val validConfigured = transcript(configured, "sbt-2-jdk17")
    val missingSummary = validConfigured.filterNot(_.contains("Failed: Total 2"))
    val inventedTaskOutput = insertBefore(
      validConfigured,
      _.startsWith("##teamcity[testStarted"),
      message("100:test:general:testQuick", "ERROR",
        s"[error] Test ${MainSuite.failedTest} failed: ${MainSuite.failureMessage}, took 0.1 sec")
    )
    Vector(missingSummary, inventedTaskOutput).foreach(lines =>
      assertViolation(collect(lines, configuredMode), SemanticCardinalityFailure))

    val custom = scenario("custom-result-logger-no-task-output")
    val customMode = custom.selection.forRuntimeProfile("sbt-1-jdk8")
    val missingMarker = transcript(custom, "sbt-1-jdk8").filterNot(_.contains("CUSTOM_TEST_RESULT_LOGGER"))
    assertViolation(collect(missingMarker, customMode), SemanticCardinalityFailure)
  }

  @Test def configuredResultSummariesKeepTheirTaskAndConfigurationOwnershipExact(): Unit = {
    Scenarios.filter(_.behavior == Behavior.ConfiguredHidden).foreach { scenario =>
      scenario.profiles.foreach { profile =>
        val mode = scenario.selection.forRuntimeProfile(profile)
        val valid = transcript(scenario, profile)
        val wrongFlow = updateUnique(
          valid,
          _.contains("Failed: Total 2"),
          s"${scenario.id} configured result count",
          _.replace(
            s":${scenario.resultConfiguration(profile)}:general:${scenario.task(profile)}",
            ":wrong:general:wrongTask"
          )
        )
        val findings = collect(wrongFlow, mode)
        assertViolation(findings, SemanticCardinalityFailure)
        assertBlockedCategory(findings, FlowOwnershipFailure)
      }
    }
  }

  @Test def compoundOutputControlMutationReportsAllIndependentFailuresTogether(): Unit = {
    val scenario = this.scenario("junit-configured-result-no-task-output")
    val profile = "sbt-2-jdk17"
    val mode = scenario.selection.forRuntimeProfile(profile)
    val valid = transcript(scenario, profile)
    val passStart = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testStarted") && line.contains(".testMe'"), "passing start")
    val passFinish = uniqueIndex(valid,
      line => line.startsWith("##teamcity[testFinished") && line.contains(".testMe'"), "passing finish")
    val wrongOrder = valid.updated(passStart, valid(passFinish)).updated(passFinish, valid(passStart))
    val wrongOwnership = updateUnique(wrongOrder, _.startsWith("##teamcity[testSuiteStarted"), "suite start",
      _.replace("flow-result", "flow-other"))
    val wrongLifecycle = updateUnique(wrongOwnership, _.startsWith("##teamcity[testSuiteFinished"), "suite finish",
      _.replace(MainSuite.name, "thisis.a.test.BrokenTest"))
    val wrongFailure = updateUnique(wrongLifecycle, _.startsWith("##teamcity[testFailed"), "failed test",
      _.replace(MainSuite.failureMessage, "changed assertion"))
    val mutated = wrongFailure ++ Vector(
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
    assertBlocked(findings, OrderingFailure, "edge:suite-finish->configured-result-count")
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Violation]"))
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Blocked]"))
  }

  private def scenario(id: String): Scenario = Scenarios.find(_.id == id).get

  private def suiteFor(kind: SuiteKind): SuiteSpec = kind match {
    case SuiteKind.Main => MainSuite
    case SuiteKind.Integration => IntegrationSuite
    case SuiteKind.Custom => CustomSuite
  }

  private def transcript(
    scenario: Scenario,
    profile: String,
    includeBridge: Boolean = false
  ): Vector[String] = {
    val suite = suiteFor(scenario.suiteKind)
    val compilation = compilationLines(scenario, profile, includeBridge)
    val taskOutput = if (scenario.behavior == Behavior.ConfiguredTaskOutput)
      failureTaskOutput(scenario, profile, suite)
    else Vector.empty
    val tests = failedTest(suite) ++ suite.passingTest.toVector.flatMap(passingTest)
    val result = resultOutput(scenario, profile, suite)
    compilation ++ Vector(suiteBoundary("testSuiteStarted", suite.name)) ++ taskOutput ++ tests ++
      Vector(suiteBoundary("testSuiteFinished", suite.name)) ++ result ++ Vector(TaskSummary)
  }

  private def compilationLines(
    scenario: Scenario,
    profile: String,
    includeBridge: Boolean
  ): Vector[String] = {
    val suffix = scenario.suiteKind match {
      case SuiteKind.Integration if isSbt2(profile) => "/target/out/jvm/scala-2.12.7/root/it-classes ..."
      case SuiteKind.Integration => "/target/scala-2.13/it-classes ..."
      case SuiteKind.Custom if isSbt2(profile) =>
        s"/target/out/jvm/scala-2.13.18/${scenario.id}/test-classes ..."
      case _ if isSbt2(profile) => s"/target/out/jvm/scala-2.12.7/${scenario.id}/test-classes ..."
      case _ => "/target/scala-2.13/test-classes ..."
    }
    val flow = "100:test:compiler"
    val info = s"[info] compiling 1 Scala source to /work$suffix"
    if (scenario.suiteKind == SuiteKind.Integration) {
      Vector(messageWithoutFlow("NORMAL", info)) ++ bridgeLines(None, includeBridge, profile) ++
        Vector(messageWithoutFlow("NORMAL", "[info] done compiling"))
    } else {
      val compiler = s"Scala compiler in Test [${scenario.id}]"
      Vector(compilationBoundary("compilationStarted", compiler, flow), message(flow, "NORMAL", info)) ++
        bridgeLines(Some(flow), includeBridge, profile) ++ Vector(
          message(flow, "NORMAL", "[info] done compiling"),
          compilationBoundary("compilationFinished", compiler, flow)
        )
    }
  }

  private def bridgeLines(
    flow: Option[String],
    include: Boolean,
    profile: String
  ): Vector[String] = if (!include) Vector.empty else {
    val integrationSbt2 = flow.isEmpty && isSbt2(profile)
    val render = (text: String) => flow.fold(messageWithoutFlow("NORMAL", text))(message(_, "NORMAL", text))
    Vector(
      render(if (integrationSbt2)
        "[info] Non-compiled module 'compiler-bridge_2.12' for Scala 2.12.7. Compiling..."
      else "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.18. Compiling..."),
      render("[info]   Compilation completed in 1.25s.")
    )
  }

  private def failureTaskOutput(
    scenario: Scenario,
    profile: String,
    suite: SuiteSpec
  ): Vector[String] = {
    val flow = s"100:test:general:${scenario.task(profile)}"
    val rendered = if (isSbt2(profile)) suite.failureMessage
    else s"java.lang.AssertionError: ${suite.failureMessage}"
    val summary = message(flow, "ERROR",
      s"[error] Test ${suite.failedTest} failed: $rendered, took 0.25 sec")
    if (isSbt2(profile)) Vector(summary)
    else Vector(summary, message(flow, "ERROR", s"[error]     at ${suite.userFrame.stripPrefix("\tat ")}")) ++
      (if (profile == "sbt-1-jdk17") Vector(
        message(flow, "ERROR",
          "[error]     at jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)"),
        message(flow, "ERROR",
          "[error]     at jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)"),
        message(flow, "ERROR",
          "[error]     at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)"),
        message(flow, "ERROR", "[error]     at java.lang.reflect.Method.invoke(Method.java:569)")
      ) else Vector.empty) ++ Vector(message(flow, "ERROR", "[error]     ..."))
  }

  private def resultOutput(scenario: Scenario, profile: String, suite: SuiteSpec): Vector[String] = {
    val task = scenario.task(profile)
    scenario.behavior match {
      case Behavior.ConfiguredTaskOutput =>
        configuredSummary("test", task, suite) :+ message(
          s"100:test:general:$task",
          "ERROR",
          s"[error] (Test / $task) sbt.TestsFailedException: Tests unsuccessful"
        )
      case Behavior.ConfiguredHidden =>
        configuredSummary(scenario.resultConfiguration(profile), task, suite)
      case Behavior.CustomConfigured => Vector(message(
        s"100:test:general:$task", "NORMAL", "[info] CUSTOM_TEST_RESULT_LOGGER"
      ))
      case Behavior.TeamCityHidden | Behavior.CustomTeamCity => Vector.empty
    }
  }

  private def configuredSummary(configuration: String, task: String, suite: SuiteSpec): Vector[String] = {
    val flow = s"100:$configuration:general:$task"
    Vector(
      message(flow, "ERROR", "[error] Failed: Total 2, Failed 1, Errors 0, Passed 1"),
      message(flow, "ERROR", "[error] Failed tests:"),
      message(flow, "ERROR", s"[error] \t${suite.name}")
    )
  }

  private def failedTest(suite: SuiteSpec): Vector[String] = Vector(
    service("testStarted", "name" -> suite.failedTest,
      "captureStandardOutput" -> "true", "flowId" -> "flow-result"),
    service("testFailed", "name" -> suite.failedTest,
      "details" -> failureDetails(suite), "flowId" -> "flow-result"),
    service("testFinished", "name" -> suite.failedTest,
      "duration" -> "17.5", "flowId" -> "flow-result")
  )

  private def passingTest(name: String): Vector[String] = Vector(
    service("testStarted", "name" -> name,
      "captureStandardOutput" -> "true", "flowId" -> "flow-result"),
    service("testFinished", "name" -> name,
      "duration" -> "8", "flowId" -> "flow-result")
  )

  private def failureDetails(suite: SuiteSpec): String =
    s"java.lang.AssertionError: ${suite.failureMessage}" +
      "\n\tat org.junit.Assert.fail(Assert.java:88)" +
      s"\n${suite.userFrame}" +
      "\n\tat com.novocode.junit.JUnitRunner.run(JUnitRunner.java:1)"

  private def suiteBoundary(kind: String, name: String): String =
    service(kind, "name" -> name, "flowId" -> "flow-result")

  private def compilationBoundary(kind: String, compiler: String, flow: String): String =
    service(kind, "compiler" -> compiler, "flowId" -> flow)

  private def message(flow: String, status: String, text: String): String =
    service("message", "status" -> status, "flowId" -> flow, "text" -> text)

  private def messageWithoutFlow(status: String, text: String): String =
    service("message", "status" -> status, "text" -> text)

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

  private def insertBeforeTaskSummary(lines: Vector[String], line: String): Vector[String] =
    lines.patch(lines.size - 1, Vector(line), 0)

  private def insertBefore(
    lines: Vector[String],
    predicate: String => Boolean,
    line: String
  ): Vector[String] = {
    val index = lines.indexWhere(predicate)
    require(index >= 0, "Expected insertion anchor.")
    lines.patch(index, Vector(line), 0)
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

  private def effectiveContract(mode: SbtOutputVerification): SbtSemanticContract = mode match {
    case Semantic(contract) => contract
    case other => throw new AssertionError(s"Expected semantic mode, got $other")
  }

  private def verify(lines: Vector[String], mode: SbtOutputVerification): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      effectiveContract(mode),
      exitCode = 1,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(
    lines: Vector[String],
    mode: SbtOutputVerification
  ): Vector[SbtSemanticFailure] = SbtSemanticOutputVerifier.collect(
    lines,
    effectiveContract(mode),
    exitCode = 1,
    delegated = SbtDelegatedVerification(processResultVerified = true)
  )

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category Violation in ${describe(findings)}",
    findings.exists(finding => finding.category == category && finding.disposition == Violation)
  )

  private def assertBlockedCategory(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category Blocked in ${describe(findings)}",
    findings.exists(finding => finding.category == category && finding.disposition == Blocked)
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
