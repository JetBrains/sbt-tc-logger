package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtJacocoSemanticContracts {
  import ObservedServiceMessageKind.*
  import PlainOutputContract.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  private val ScenarioId = "jacoco"
  private val SuiteName = "fixture.jacoco.PointTest"
  private val TestName = s"$SuiteName.movesPoint"

  private final case class Profile(
    id: String,
    sbt2: Boolean,
    mainClasses: String,
    testClasses: String,
    instrumentedClasses: String,
    reportDirectory: String,
    missedLines: String
  )

  private val Sbt1 = Profile(
    "sbt-1-jdk8",
    sbt2 = false,
    "/target/scala-2.13/classes",
    "/target/scala-2.13/test-classes",
    "/target/scala-2.13/jacoco/instrumented-classes",
    "/target/scala-2.13/jacoco/report",
    "5 of 10"
  )
  private val Profiles = Vector(
    Sbt1,
    Sbt1.copy(id = "sbt-1-jdk17"),
    Profile(
      "sbt-2-jdk17",
      sbt2 = true,
      "/target/out/jvm/scala-2.12.21/jacoco/classes",
      "/target/out/jvm/scala-2.12.21/jacoco/test-classes",
      "/target/out/jvm/scala-2.12.21/jacoco/jacoco/instrumented-classes",
      "/target/out/jvm/scala-2.12.21/jacoco/jacoco/report",
      "6 of 12"
    )
  )
  private val ProfilesById = Profiles.map(profile => profile.id -> profile).toMap

  lazy val Report: SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = ExactTranscript,
    runtimeProfileOverrides = Profiles.map(profile =>
      profile.id -> Semantic(contract(profile))
    ).toMap
  )

  private[logger] def semanticContractFor(runtimeProfile: String): SbtSemanticContract =
    contract(profile(runtimeProfile))

  private def profile(runtimeProfile: String): Profile = ProfilesById.getOrElse(
    runtimeProfile,
    throw new IllegalArgumentException(s"No JaCoCo semantic profile for '$runtimeProfile'.")
  )

  private def contract(profile: Profile): SbtSemanticContract = {
    val buildId = SemanticBindingKey.buildId(s"$ScenarioId-build")
    val workspace = SemanticBindingKey.path(s"$ScenarioId-workspace")
    val suiteFlow = SemanticBindingKey.flow(s"$ScenarioId-suite-flow")
    val mainFlow = embedded("", buildId, ":compile:compiler")
    val testCompilerFlow = embedded("", buildId, ":test:compiler")
    val mainStart = ExpectedSemanticEvent("main-start", CompilationStarted,
      "compiler" -> exact("Scala compiler [jacoco]"))
    val mainInfo = normalMessage("main-info", mainFlow,
      embedded("[info] compiling 2 Scala sources to ", workspace, profile.mainClasses + " ..."))
    val sbt1Warnings = if (profile.sbt2) Vector.empty else Vector(
      inspectionType,
      warningMessage("main-deprecation", mainFlow,
        "[warn] 2 deprecations (since 2.13.0); re-run with -deprecation for details")
    )
    val mainDone = normalMessage("main-done", mainFlow, exact("[info] done compiling"))
    val warningCount = if (profile.sbt2) Vector.empty else Vector(
      warningMessage("main-warning-count", mainFlow, "[warn] one warning found")
    )
    val mainFinish = ExpectedSemanticEvent("main-finish", CompilationFinished,
      "compiler" -> exact("Scala compiler [jacoco]"))
    val main = Vector(mainStart, mainInfo) ++ sbt1Warnings ++ Vector(mainDone) ++ warningCount ++ Vector(mainFinish)

    val testCompileStart = ExpectedSemanticEvent("test-compile-start", CompilationStarted,
      "compiler" -> exact("Scala compiler in Test [jacoco]"))
    val testCompileInfo = normalMessage("test-compile-info", testCompilerFlow,
      embedded("[info] compiling 1 Scala source to ", workspace, profile.testClasses + " ..."))
    val testCompileDone = normalMessage("test-compile-done", testCompilerFlow, exact("[info] done compiling"))
    val testCompileFinish = ExpectedSemanticEvent("test-compile-finish", CompilationFinished,
      "compiler" -> exact("Scala compiler in Test [jacoco]"))
    val testCompilation = Vector(testCompileStart, testCompileInfo, testCompileDone, testCompileFinish)

    val instrument = normalMessage(
      "instrument-classes",
      embedded("", buildId, ":test:general:fullClasspath"),
      embedded("[info] Instrumenting 2 classes to ", workspace, profile.instrumentedClasses)
    )
    val suiteStart = ExpectedSemanticEvent("suite-start", TestSuiteStarted,
      "name" -> exact(SuiteName))
    val testStart = ExpectedSemanticEvent("test-start", TestStarted,
      "name" -> exact(TestName),
      "captureStandardOutput" -> exact("true"))
    val testFinish = ExpectedSemanticEvent("test-finish", TestFinished,
      "name" -> exact(TestName),
      "duration" -> unsignedDuration)
    val suiteFinish = ExpectedSemanticEvent("suite-finish", TestSuiteFinished,
      "name" -> exact(SuiteName))
    val report = normalMessage(
      "coverage-report",
      embedded("", buildId, ":test:general:jacocoReport"),
      embedded(coveragePrefix(profile.missedLines), workspace, profile.reportDirectory + coverageSuffix)
    )
    val testRun = Vector(instrument, suiteStart, testStart, testFinish, suiteFinish, report)
    val events = main ++ testCompilation ++ testRun

    SbtSemanticContract(
      events = events,
      happensBefore = chainEdges(events.map(_.id)),
      lifecycles = Vector(
        SemanticLifecycleRule.compilation(
          "jacoco-main-compilation",
          mainStart.id.value,
          main.collect {
            case event if event.kind == BuildLogMessage && event.attributes.contains("flowId" -> mainFlow) =>
              event.id.value
          },
          mainFinish.id.value,
          mainFlow
        ),
        SemanticLifecycleRule.compilation(
          "jacoco-test-compilation",
          testCompileStart.id.value,
          Seq(testCompileInfo.id.value, testCompileDone.id.value),
          testCompileFinish.id.value,
          testCompilerFlow
        ),
        SemanticLifecycleRule.suite(
          "jacoco-suite",
          suiteStart.id.value,
          Seq(testStart.id.value, testFinish.id.value),
          suiteFinish.id.value,
          suiteFlow
        ),
        SemanticLifecycleRule.test(
          "jacoco-test",
          testStart.id.value,
          Seq.empty,
          testFinish.id.value,
          suiteFlow
        )
      ),
      optionalGroups = Vector(
        compilerBridgeGroup("main", mainFlow, mainInfo.id, sbt1Warnings.headOption.map(_.id).getOrElse(mainDone.id)),
        compilerBridgeGroup("test", testCompilerFlow, testCompileInfo.id, testCompileDone.id)
      ),
      plainOutput = if (profile.sbt2)
        Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
      else RejectAll
    )
  }

  private def coveragePrefix(missedLines: String): String =
    "[info] \n" +
      "[info] ------- Jacoco Coverage Report -------\n" +
      "[info] \n" +
      s"[info] Lines: 50% (>= required 0.0%) covered, $missedLines missed, OK\n" +
      "[info] Instructions: 31.86% (>= required 0.0%) covered, 77 of 113 missed, OK\n" +
      "[info] Branches: 0% (>= required 0.0%) covered, 0 of 0 missed, OK\n" +
      "[info] Methods: 46.15% (>= required 0.0%) covered, 7 of 13 missed, OK\n" +
      "[info] Complexity: 46.15% (>= required 0.0%) covered, 7 of 13 missed, OK\n" +
      "[info] Class: 50% (>= required 0.0%) covered, 1 of 2 missed, OK\n" +
      "[info] \n" +
      "[info] Check "

  private val coverageSuffix = " for detailed report\n[info]  "

  private def inspectionType: ExpectedSemanticEvent = ExpectedSemanticEvent(
    "main-inspection-type",
    InspectionType,
    "id" -> exact("SbtCompileProblem"),
    "name" -> exact("sbt compile problem"),
    "description" -> exact("Compile problems"),
    "category" -> exact("Compile problems")
  )

  private def normalMessage(
    id: String,
    flow: SemanticValuePattern,
    text: SemanticValuePattern
  ): ExpectedSemanticEvent = ExpectedSemanticEvent(id, BuildLogMessage,
    "status" -> exact("NORMAL"),
    "flowId" -> flow,
    "text" -> text)

  private def warningMessage(
    id: String,
    flow: SemanticValuePattern,
    text: String
  ): ExpectedSemanticEvent = ExpectedSemanticEvent(id, BuildLogMessage,
    "status" -> exact("WARNING"),
    "flowId" -> flow,
    "text" -> exact(text))

  private def compilerBridgeGroup(
    label: String,
    ownership: SemanticValuePattern,
    before: SemanticEventId,
    after: SemanticEventId
  ): OptionalSemanticEventGroup = {
    val announcement = normalMessage(
      s"$label-bridge-announcement",
      ownership,
      compilerBridgeAnnouncement
    )
    val completion = normalMessage(
      s"$label-bridge-completion",
      ownership,
      compilerBridgeCompletion
    )
    OptionalSemanticEventGroup(
      s"$label-compiler-bridge",
      Vector(announcement, completion),
      Set(
        HappensBefore(before, announcement.id),
        HappensBefore(announcement.id, completion.id),
        HappensBefore(completion.id, after)
      ),
      ownership = Some(ownership)
    )
  }

  private def chainEdges(ids: Vector[SemanticEventId]): Set[HappensBefore] =
    ids.sliding(2).collect { case Vector(before, after) => HappensBefore(before, after) }.toSet
}
