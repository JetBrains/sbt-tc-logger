package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtCompilationOutputSemanticContracts {
  import ObservedServiceMessageKind.*
  import CompilerDiagnosticLevel.*
  import PlainOutputContract.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*
  import StructuredSbtDebugKind.*

  private enum OutputLayout {
    case Sbt1, Sbt2

    def mainClasses(scenarioId: String): String = this match {
      case Sbt1 => "/target/scala-2.13/classes"
      case Sbt2 => s"/target/out/jvm/scala-2.12.20/$scenarioId/classes"
    }

    def testClasses(scenarioId: String): String = this match {
      case Sbt1 => "/target/scala-2.13/test-classes"
      case Sbt2 => s"/target/out/jvm/scala-2.12.20/$scenarioId/test-classes"
    }
  }

  private enum DebugShape(
    val compilerEvents: Vector[StructuredSbtDebugKind],
    val incOptionsEvents: Vector[StructuredSbtDebugKind]
  ) {
    case Sbt1_4 extends DebugShape(
      BaseCompilerDebug,
      Vector(CreatedClassFileManager, RemoveTemporaryDirectory)
    )
    case ModernSbt1 extends DebugShape(
      BaseCompilerDebug,
      Vector(
        CreatedClassFileManager,
        AboutToDeleteClassFiles,
        BackupClassFiles,
        CreatedClassFileManager,
        RemoveTemporaryDirectory
      )
    )
    case Sbt2 extends DebugShape(
      BaseCompilerDebug :+ WroteProducts,
      Vector(
        CreatedClassFileManager,
        AboutToDeleteClassFiles,
        BackupClassFiles,
        CreatedClassFileManager,
        RemoveTemporaryDirectory
      )
    )
  }

  private final case class Profile(
    outputLayout: OutputLayout,
    preserveProblem: String,
    debugShape: DebugShape,
    isSbt2: Boolean
  )

  private val BaseCompilerDebug = Vector(
    IncrementalHeader,
    IncrementalCompile,
    PreviousStamps,
    CurrentSources,
    InitialChanges,
    FullCompilation
  )
  private val DependencyDebug = Vector(DependencyCheck, DependencyUpdate, DependencyDone)

  private val RuntimeProfiles = Map(
    "sbt-1.4-jdk8" -> Profile(OutputLayout.Sbt1, "invalid literal number", DebugShape.Sbt1_4, isSbt2 = false),
    "sbt-1-jdk8" -> Profile(OutputLayout.Sbt1, "invalid literal number", DebugShape.ModernSbt1, isSbt2 = false),
    "sbt-1-jdk17" -> Profile(OutputLayout.Sbt1, "invalid literal number", DebugShape.ModernSbt1, isSbt2 = false),
    "sbt-2-jdk17" -> Profile(OutputLayout.Sbt2, "Invalid literal number", DebugShape.Sbt2, isSbt2 = true)
  )

  lazy val TestCompilationFailure: SbtOutputVerificationSelection = selection() { (_, profile) =>
    Semantic(testCompilationFailureContract(profile))
  }

  lazy val PreserveConsole: SbtOutputVerificationSelection = selection(Set("sbt-1.4-jdk8")) {
    (_, profile) => Hybrid(preserveConsoleContract(profile), preserveConsolePlainOutput(profile))
  }

  lazy val CompilerLogLevelError: SbtOutputVerificationSelection = selection() { (_, _) =>
    Hybrid(
      SbtSemanticContract(events = Vector.empty, plainOutput = DelegatedToHybrid),
      RejectAll
    )
  }

  lazy val CompilerLogLevelDebug: SbtOutputVerificationSelection = selection() { (_, profile) =>
    Hybrid(compilerLogLevelDebugContract(profile), RejectAll)
  }

  private[logger] def testCompilationFailureContract(runtimeProfile: String): SbtSemanticContract =
    testCompilationFailureContract(profile(runtimeProfile))

  private[logger] def preserveConsoleContract(runtimeProfile: String): SbtSemanticContract =
    preserveConsoleContract(profile(runtimeProfile))

  private[logger] def preserveConsolePlainOutput(runtimeProfile: String): PlainOutputContract =
    preserveConsolePlainOutput(profile(runtimeProfile))

  private[logger] def compilerLogLevelDebugContract(runtimeProfile: String): SbtSemanticContract =
    compilerLogLevelDebugContract(profile(runtimeProfile))

  private def selection(
    exactProfiles: Set[String] = Set.empty
  )(
    mode: (String, Profile) => SbtOutputVerification
  ): SbtOutputVerificationSelection = {
    require(
      exactProfiles.subsetOf(RuntimeProfiles.keySet),
      s"Unknown exact compilation-output profiles: ${(exactProfiles -- RuntimeProfiles.keySet).toVector.sorted.mkString(", ")}."
    )
    SbtOutputVerificationSelection(
      defaultMode = ExactTranscript,
      runtimeProfileOverrides = RuntimeProfiles.iterator.collect {
        case (runtimeProfile, shape) if !exactProfiles.contains(runtimeProfile) =>
          runtimeProfile -> mode(runtimeProfile, shape)
      }.toMap
    )
  }

  private def profile(runtimeProfile: String): Profile = RuntimeProfiles.getOrElse(
    runtimeProfile,
    throw new IllegalArgumentException(s"No compilation-output semantic profile for '$runtimeProfile'.")
  )

  private def testCompilationFailureContract(profile: Profile): SbtSemanticContract = {
    val scenarioId = "test-compilation-failure"
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val outputBase = SemanticBindingKey.path(s"$scenarioId-output-base")
    val sourceBase = SemanticBindingKey.path(s"$scenarioId-source-base")
    val mainOwnership = compilerFlow(buildId, ":compile:compiler")
    val testOwnership = compilerFlow(buildId, ":test:compiler")
    val mainStart = ExpectedSemanticEvent(s"$scenarioId-main-start", CompilationStarted,
      "compiler" -> exact(s"Scala compiler [$scenarioId]"))
    val mainInfo = ExpectedSemanticEvent(s"$scenarioId-main-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded("[info] compiling 1 Scala source to ", outputBase,
        profile.outputLayout.mainClasses(scenarioId) + " ..."))
    val mainDone = ExpectedSemanticEvent(s"$scenarioId-main-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> exact("[info] done compiling"))
    val mainFinish = ExpectedSemanticEvent(s"$scenarioId-main-finish", CompilationFinished,
      "compiler" -> exact(s"Scala compiler [$scenarioId]"))
    val testStart = ExpectedSemanticEvent(s"$scenarioId-test-start", CompilationStarted,
      "compiler" -> exact(s"Scala compiler in Test [$scenarioId]"))
    val testInfo = ExpectedSemanticEvent(s"$scenarioId-test-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded("[info] compiling 1 Scala source to ", outputBase,
        profile.outputLayout.testClasses(scenarioId) + " ..."))
    val problemType = inspectionType(s"$scenarioId-problem-type")
    val sourceSuffix = "/src/test/scala/BrokenTest.scala"
    val inspection = ExpectedSemanticEvent(s"$scenarioId-inspection", Inspection,
      "SEVERITY" -> exact("ERROR"),
      "line" -> exact("2"),
      "typeId" -> exact("SbtCompileProblem"),
      "message" -> exact("type mismatch;\n found   : Int(1)\n required: String"),
      "file" -> embedded("", sourceBase, sourceSuffix))
    val detail = ExpectedSemanticEvent(s"$scenarioId-detail", BuildLogMessage,
      "status" -> exact("ERROR"),
      "text" -> embedded(
        "[error] ",
        sourceBase,
        sourceSuffix + ":2: type mismatch;\n" +
          "[error]  found   : Int(1)\n" +
          "[error]  required: String\n" +
          "[error]   val value: String = 1\n" +
          "[error]                       ^"
      ))
    val errorCount = ExpectedSemanticEvent(s"$scenarioId-error-count", BuildLogMessage,
      "status" -> exact("ERROR"),
      "text" -> exact("[error] one error found"))
    val testFinish = ExpectedSemanticEvent(s"$scenarioId-test-finish", CompilationFinished,
      "compiler" -> exact(s"Scala compiler in Test [$scenarioId]"))
    val taskFailure = ExpectedSemanticEvent(s"$scenarioId-task-failure", BuildLogMessage,
      "status" -> exact("ERROR"),
      "text" -> exact("[error] (Test / compileIncremental) Compilation failed"))
    val main = Vector(mainStart, mainInfo, mainDone, mainFinish)
    val test = Vector(testStart, testInfo, problemType, inspection, detail, errorCount, testFinish, taskFailure)

    SbtSemanticContract(
      events = main ++ test,
      happensBefore = chainEdges(main.map(_.id)) ++ chainEdges(test.map(_.id)) +
        HappensBefore(mainFinish.id, testStart.id),
      lifecycles = Vector(
        SemanticLifecycleRule.compilation(
          s"$scenarioId-main", mainStart.id.value, Seq(mainInfo.id.value, mainDone.id.value),
          mainFinish.id.value, mainOwnership),
        SemanticLifecycleRule.compilation(
          s"$scenarioId-test", testStart.id.value, Seq(testInfo.id.value, detail.id.value, errorCount.id.value),
          testFinish.id.value, testOwnership)
      ),
      optionalGroups = Vector(
        compilerBridgeGroup(s"$scenarioId-main", mainOwnership, mainInfo.id, mainDone.id),
        compilerBridgeGroup(s"$scenarioId-test", testOwnership, testInfo.id, problemType.id)
      ),
      plainOutput = summaryContract(if (profile.isSbt2) 1 else 0)
    )
  }

  private def preserveConsoleContract(profile: Profile): SbtSemanticContract = {
    val scenarioId = "compilation-preserve-console"
    val sourceBase = SemanticBindingKey.path(s"$scenarioId-source-base")
    val sourceSuffix = "/src/main/scala/BrokenHelloWorld.scala"
    SbtSemanticContract(
      events = Vector(
        inspectionType(s"$scenarioId-problem-type"),
        ExpectedSemanticEvent(s"$scenarioId-inspection", Inspection,
          "SEVERITY" -> exact("ERROR"),
          "line" -> exact("2"),
          "typeId" -> exact("SbtCompileProblem"),
          "message" -> exact(profile.preserveProblem),
          "file" -> embedded("", sourceBase, sourceSuffix))
      ),
      happensBefore = Set(HappensBefore(s"$scenarioId-problem-type", s"$scenarioId-inspection")),
      plainOutput = DelegatedToHybrid
    )
  }

  private def preserveConsolePlainOutput(profile: Profile): PlainOutputContract = {
    val scenarioId = "compilation-preserve-console"
    val workspace = SemanticBindingKey.path(s"$scenarioId-plain-workspace")
    val sourceSuffix = "/src/main/scala/BrokenHelloWorld.scala"
    val before = (pattern: PlainOutputPattern) => BeforeServiceMessages(pattern)
    val after = (pattern: PlainOutputPattern) => AfterServiceMessages(pattern)
    Patterns(Vector(
      before(CompilerCompileInfo(workspace, profile.outputLayout.mainClasses(scenarioId))),
      OptionalGroup(s"$scenarioId-compiler-bridge", Vector(
        before(PlainOutputPattern.CompilerBridgeAnnouncement),
        before(PlainOutputPattern.CompilerBridgeCompletion)
      )),
      after(CompilerInspectionDiagnostic(workspace, sourceSuffix, Error, 35)),
      after(PlainOutputPattern.Exact("[error]   def main(args: Array[String]) = 123println(\"Hello, World!\")")),
      after(PlainOutputPattern.Exact("[error]                                   ^")),
      after(PlainOutputPattern.Exact("[error] one error found")),
      after(PlainOutputPattern.Exact("[error] (Compile / compileIncremental) Compilation failed")),
      after(SbtTaskSummary)
    ))
  }

  private def compilerLogLevelDebugContract(profile: Profile): SbtSemanticContract = {
    val scenarioId = "compiler-log-level-debug"
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val dependency = structuredDebugEvents(
      scenarioId,
      "dependency",
      DependencyDebug,
      compilerFlow(buildId, ":global:dependency")
    )
    val incOptions = structuredDebugEvents(
      scenarioId,
      "inc-options",
      profile.debugShape.incOptionsEvents,
      compilerFlow(buildId, ":compile:general:incOptions")
    )
    val compiler = structuredDebugEvents(
      scenarioId,
      "compiler",
      profile.debugShape.compilerEvents,
      compilerFlow(buildId, ":compile:compiler")
    )
    val start = ExpectedSemanticEvent(s"$scenarioId-start", CompilationStarted,
      "compiler" -> exact(s"Scala compiler [$scenarioId]"))
    val finish = ExpectedSemanticEvent(s"$scenarioId-finish", CompilationFinished,
      "compiler" -> exact(s"Scala compiler [$scenarioId]"))

    SbtSemanticContract(
      events = dependency ++ incOptions ++ Vector(start) ++ compiler ++ Vector(finish),
      happensBefore = chainEdges(dependency.map(_.id)) ++
        chainEdges(incOptions.map(_.id)) ++
        chainEdges((Vector(start) ++ compiler ++ Vector(finish)).map(_.id)),
      lifecycles = Vector(SemanticLifecycleRule.compilation(
        scenarioId,
        start.id.value,
        compiler.map(_.id.value),
        finish.id.value,
        compilerFlow(buildId, ":compile:compiler")
      )),
      plainOutput = DelegatedToHybrid
    )
  }

  private def structuredDebugEvents(
    scenarioId: String,
    role: String,
    kinds: Vector[StructuredSbtDebugKind],
    ownership: SemanticValuePattern
  ): Vector[ExpectedSemanticEvent] = kinds.zipWithIndex.map { case (kind, index) =>
    ExpectedSemanticEvent(s"$scenarioId-$role-${kind.id}-$index", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "flowId" -> ownership,
      "text" -> structuredSbtDebug(kind))
  }

  private def inspectionType(id: String): ExpectedSemanticEvent =
    ExpectedSemanticEvent(id, InspectionType,
      "id" -> exact("SbtCompileProblem"),
      "name" -> exact("sbt compile problem"),
      "description" -> exact("Compile problems"),
      "category" -> exact("Compile problems"))

  private def compilerBridgeGroup(
    name: String,
    ownership: SemanticValuePattern,
    after: SemanticEventId,
    before: SemanticEventId
  ): OptionalSemanticEventGroup = {
    val announcement = ExpectedSemanticEvent(s"$name-bridge-announcement", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> compilerBridgeAnnouncement)
    val completion = ExpectedSemanticEvent(s"$name-bridge-completion", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> compilerBridgeCompletion)
    OptionalSemanticEventGroup(
      s"$name-compiler-bridge",
      Vector(announcement, completion),
      Set(
        HappensBefore(after, announcement.id),
        HappensBefore(announcement.id, completion.id),
        HappensBefore(completion.id, before)
      ),
      Some(ownership)
    )
  }

  private def compilerFlow(buildId: SemanticBindingKey, suffix: String): SemanticValuePattern =
    embedded("", buildId, suffix)

  private def chainEdges(ids: Vector[SemanticEventId]): Set[HappensBefore] =
    ids.sliding(2).collect { case Vector(before, after) => HappensBefore(before, after) }.toSet

  private def summaryContract(count: Int): PlainOutputContract =
    if (count == 0) RejectAll
    else Patterns(Vector.fill(count)(AfterServiceMessages(SbtTaskSummary)))
}
