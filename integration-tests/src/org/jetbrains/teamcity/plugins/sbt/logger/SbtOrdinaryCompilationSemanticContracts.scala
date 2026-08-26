package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtOrdinaryCompilationSemanticContracts {
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  private val Sbt2Profile = "sbt-2-jdk17"

  lazy val Success: SbtOutputVerificationSelection = selection(
    "compilation-success",
    exactProfiles = Set(Sbt2Profile)
  )
  lazy val Failure: SbtOutputVerificationSelection = selection("compilation-failure")
  lazy val Warnings: SbtOutputVerificationSelection = selection("compilation-warnings")
  lazy val Subproject: SbtOutputVerificationSelection = selection("compilation-subproject")
  lazy val NoBuildFile: SbtOutputVerificationSelection = selection("project-no-build-file")
  lazy val Incremental: SbtOutputVerificationSelection = selection("compile-incremental")
  lazy val UpToDate: SbtOutputVerificationSelection = selection("compilation-up-to-date")
  lazy val Inputs: SbtOutputVerificationSelection = selection("compile-inputs")

  private enum OutputLayout {
    case Sbt1, Sbt2

    def outputSuffix(scenarioId: String, project: Option[String]): String = (this, project) match {
      case (Sbt1, None) => "/target/scala-2.13/classes ..."
      case (Sbt1, Some(value)) => s"/$value/target/scala-2.13/classes ..."
      case (Sbt2, None) => s"/target/out/jvm/scala-2.12.20/$scenarioId/classes ..."
      case (Sbt2, Some(value)) => s"/target/out/jvm/scala-2.12.20/$value/classes ..."
    }
  }

  private final case class Profile(
    outputLayout: OutputLayout,
    failureProblem: String,
    isSbt2: Boolean
  )

  private final case class CompilationPart(
    events: Vector[ExpectedSemanticEvent],
    edges: Set[HappensBefore],
    lifecycle: SemanticLifecycleRule,
    optionalGroups: Vector[OptionalSemanticEventGroup]
  )

  private val Sbt1ProfileShape = Profile(OutputLayout.Sbt1, "invalid literal number", isSbt2 = false)
  private val Sbt2ProfileShape = Profile(OutputLayout.Sbt2, "Invalid literal number", isSbt2 = true)

  private val RuntimeProfiles = Map(
    "sbt-1.4-jdk8" -> Sbt1ProfileShape,
    "sbt-1-jdk8" -> Sbt1ProfileShape,
    "sbt-1-jdk17" -> Sbt1ProfileShape,
    Sbt2Profile -> Sbt2ProfileShape
  )

  private val ScenarioIds = Set(
    "compilation-success",
    "compilation-failure",
    "compilation-warnings",
    "compilation-subproject",
    "project-no-build-file",
    "compile-incremental",
    "compilation-up-to-date",
    "compile-inputs"
  )

  private[logger] def semanticContractFor(
    scenarioId: String,
    runtimeProfile: String
  ): SbtSemanticContract = {
    require(ScenarioIds.contains(scenarioId), s"No ordinary-compilation semantic scenario for '$scenarioId'.")
    val profile = RuntimeProfiles.getOrElse(
      runtimeProfile,
      throw new IllegalArgumentException(s"No ordinary-compilation semantic profile for '$runtimeProfile'.")
    )
    scenarioId match {
      case "compilation-success" | "compile-incremental" | "compilation-up-to-date" =>
        successfulCompilationContract(scenarioId, profile)
      case "compilation-failure" => failureContract(profile)
      case "compilation-warnings" => warningsContract(profile)
      case "compilation-subproject" => subprojectContract(profile)
      case "project-no-build-file" => noBuildFileContract(profile)
      case "compile-inputs" => inputsContract(profile)
    }
  }

  private def selection(
    scenarioId: String,
    exactProfiles: Set[String] = Set.empty
  ): SbtOutputVerificationSelection = {
    require(
      exactProfiles.subsetOf(RuntimeProfiles.keySet),
      s"Unknown exact ordinary-compilation profiles: ${exactProfiles.diff(RuntimeProfiles.keySet).toVector.sorted.mkString(", ")}."
    )
    val semanticOverrides = RuntimeProfiles.keysIterator
      .filterNot(exactProfiles.contains)
      .map(runtimeProfile => runtimeProfile -> Semantic(semanticContractFor(scenarioId, runtimeProfile)))
      .toMap
    SbtOutputVerificationSelection(
      defaultMode = ExactTranscript,
      runtimeProfileOverrides = semanticOverrides
    )
  }

  private def successfulCompilationContract(
    scenarioId: String,
    profile: Profile
  ): SbtSemanticContract = {
    val part = ordinaryCompilationPart(scenarioId, profile)
    assemble(part, summaryCount = scenarioId match {
      case "compilation-up-to-date" if profile.isSbt2 => 2
      case _ if profile.isSbt2 => 1
      case _ => 0
    })
  }

  private def subprojectContract(profile: Profile): SbtSemanticContract = {
    val part = ordinaryCompilationPart(
      scenarioId = "compilation-subproject",
      profile = profile,
      compilerProject = Some("backend")
    )
    assemble(part, summaryCount = if (profile.isSbt2) 1 else 0)
  }

  private def ordinaryCompilationPart(
    scenarioId: String,
    profile: Profile,
    compilerProject: Option[String] = None
  ): CompilationPart = {
    val owner = compilerProject.getOrElse(scenarioId)
    val compiler = s"Scala compiler [$owner]"
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val outputBase = SemanticBindingKey.path(s"$scenarioId-output-base")
    val ownership = compilerFlow(buildId)
    val start = ExpectedSemanticEvent(s"$scenarioId-start", CompilationStarted,
      "compiler" -> exact(compiler),
      "flowId" -> ownership)
    val info = ExpectedSemanticEvent(s"$scenarioId-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "flowId" -> ownership,
      "text" -> embedded(
        "[info] compiling 1 Scala source to ",
        outputBase,
        profile.outputLayout.outputSuffix(scenarioId, compilerProject)
      ))
    val done = ExpectedSemanticEvent(s"$scenarioId-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "flowId" -> ownership,
      "text" -> exact("[info] done compiling"))
    val finish = ExpectedSemanticEvent(s"$scenarioId-finish", CompilationFinished,
      "compiler" -> exact(compiler),
      "flowId" -> ownership)

    CompilationPart(
      events = Vector(start, info, done, finish),
      edges = chainEdges(Vector(start.id, info.id, done.id, finish.id)),
      lifecycle = SemanticLifecycleRule.compilation(
        scenarioId,
        start.id.value,
        Seq(info.id.value, done.id.value),
        finish.id.value,
        ownership
      ),
      optionalGroups = Vector(compilerBridgeGroup(scenarioId, ownership, info.id, done.id))
    )
  }

  private def failureContract(profile: Profile): SbtSemanticContract = {
    val scenarioId = "compilation-failure"
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val sourceBase = SemanticBindingKey.path(s"$scenarioId-source-base")
    val ownership = compilerFlow(buildId)
    val compiler = s"Scala compiler [$scenarioId]"
    val sourceSuffix = "/src/main/scala/BrokenHelloWorld.scala"
    val start = ExpectedSemanticEvent(s"$scenarioId-start", CompilationStarted,
      "compiler" -> exact(compiler),
      "flowId" -> ownership)
    val problemType = inspectionType(s"$scenarioId-problem-type")
    val inspection = ExpectedSemanticEvent(s"$scenarioId-inspection", Inspection,
      "SEVERITY" -> exact("ERROR"),
      "line" -> exact("4"),
      "typeId" -> exact("SbtCompileProblem"),
      "message" -> exact(profile.failureProblem),
      "file" -> embedded("", sourceBase, sourceSuffix))
    val detail = ExpectedSemanticEvent(s"$scenarioId-detail", BuildLogMessage,
      "status" -> exact("ERROR"),
      "flowId" -> ownership,
      "text" -> embedded(
        "[error] ",
        sourceBase,
        sourceSuffix + s":4: ${profile.failureProblem}\n" +
          "[error]   def main(args: Array[String]) = 123println(\"Hello, World!\")\n" +
          "[error]                                   ^"
      ))
    val count = ExpectedSemanticEvent(s"$scenarioId-count", BuildLogMessage,
      "status" -> exact("ERROR"),
      "flowId" -> ownership,
      "text" -> exact("[error] one error found"))
    val finish = ExpectedSemanticEvent(s"$scenarioId-finish", CompilationFinished,
      "compiler" -> exact(compiler),
      "flowId" -> ownership)
    val taskFailure = ExpectedSemanticEvent(s"$scenarioId-task-failure", BuildLogMessage,
      "status" -> exact("ERROR"),
      "text" -> exact("[error] (Compile / compileIncremental) Compilation failed"))
    val ordered = Vector(start, problemType, inspection, detail, count, finish, taskFailure)

    SbtSemanticContract(
      events = ordered,
      happensBefore = chainEdges(ordered.map(_.id)),
      lifecycles = Vector(SemanticLifecycleRule.compilation(
        scenarioId,
        start.id.value,
        Seq(detail.id.value, count.id.value),
        finish.id.value,
        ownership
      )),
      plainOutput = summaryContract(if (profile.isSbt2) 1 else 0)
    )
  }

  private def warningsContract(profile: Profile): SbtSemanticContract = {
    val scenarioId = "compilation-warnings"
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val outputBase = SemanticBindingKey.path(s"$scenarioId-output-base")
    val sourceBase = SemanticBindingKey.path(s"$scenarioId-source-base")
    val ownership = compilerFlow(buildId)
    val compiler = s"Scala compiler [$scenarioId]"
    val sourceSuffix = "/AWarning.scala"
    val start = ExpectedSemanticEvent(s"$scenarioId-start", CompilationStarted,
      "compiler" -> exact(compiler),
      "flowId" -> ownership)
    val info = ExpectedSemanticEvent(s"$scenarioId-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "flowId" -> ownership,
      "text" -> embedded(
        "[info] compiling 1 Scala source to ",
        outputBase,
        profile.outputLayout.outputSuffix(scenarioId, None)
      ))
    val problemType = inspectionType(s"$scenarioId-problem-type")
    val warningPairs = Vector(
      warningPair(
        scenarioId,
        ordinal = 1,
        line = 7,
        problem = "match may not be exhaustive.\nIt would fail on the following input: C",
        detail = "match may not be exhaustive.\n" +
          "[warn] It would fail on the following input: C\n" +
          "[warn]   def printThing(t: Thing) = t match {\n" +
          "[warn]                              ^",
        sourceBase,
        sourceSuffix,
        ownership
      ),
      warningPair(
        scenarioId,
        ordinal = 2,
        line = 17,
        problem = "patterns after a variable pattern cannot match (SLS 8.1.1)",
        detail = "patterns after a variable pattern cannot match (SLS 8.1.1)\n" +
          "[warn]     case anything =>\n" +
          "[warn]          ^",
        sourceBase,
        sourceSuffix,
        ownership
      ),
      warningPair(
        scenarioId,
        ordinal = 3,
        line = 18,
        problem = "unreachable code due to variable pattern 'anything' on line 17",
        detail = "unreachable code due to variable pattern 'anything' on line 17\n" +
          "[warn]     case unreached =>\n" +
          "[warn]                    ^",
        sourceBase,
        sourceSuffix,
        ownership
      ),
      warningPair(
        scenarioId,
        ordinal = 4,
        line = 18,
        problem = "unreachable code",
        detail = "unreachable code\n" +
          "[warn]     case unreached =>\n" +
          "[warn]                    ^",
        sourceBase,
        sourceSuffix,
        ownership
      )
    )
    val done = ExpectedSemanticEvent(s"$scenarioId-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "flowId" -> ownership,
      "text" -> exact("[info] done compiling"))
    val count = ExpectedSemanticEvent(s"$scenarioId-count", BuildLogMessage,
      "status" -> exact("WARNING"),
      "flowId" -> ownership,
      "text" -> exact("[warn] four warnings found"))
    val finish = ExpectedSemanticEvent(s"$scenarioId-finish", CompilationFinished,
      "compiler" -> exact(compiler),
      "flowId" -> ownership)
    val pairEvents = warningPairs.flatMap(pair => Vector(pair._1, pair._2))
    val ordered = Vector(start, info, problemType) ++ pairEvents ++ Vector(done, count, finish)
    val lifecycleMembers = Vector(info) ++ warningPairs.map(_._2) ++ Vector(done, count)

    SbtSemanticContract(
      events = ordered,
      happensBefore = chainEdges(ordered.map(_.id)),
      lifecycles = Vector(SemanticLifecycleRule.compilation(
        scenarioId,
        start.id.value,
        lifecycleMembers.map(_.id.value),
        finish.id.value,
        ownership
      )),
      optionalGroups = Vector(compilerBridgeGroup(scenarioId, ownership, info.id, problemType.id)),
      plainOutput = summaryContract(1)
    )
  }

  private def warningPair(
    scenarioId: String,
    ordinal: Int,
    line: Int,
    problem: String,
    detail: String,
    sourceBase: SemanticBindingKey,
    sourceSuffix: String,
    ownership: SemanticValuePattern
  ): (ExpectedSemanticEvent, ExpectedSemanticEvent) = {
    val inspection = ExpectedSemanticEvent(s"$scenarioId-inspection-$ordinal", Inspection,
      "SEVERITY" -> exact("WARNING"),
      "line" -> exact(line.toString),
      "typeId" -> exact("SbtCompileProblem"),
      "message" -> exact(problem),
      "file" -> embedded("", sourceBase, sourceSuffix))
    val message = ExpectedSemanticEvent(s"$scenarioId-detail-$ordinal", BuildLogMessage,
      "status" -> exact("WARNING"),
      "flowId" -> ownership,
      "text" -> embedded("[warn] ", sourceBase, s"$sourceSuffix:$line: $detail"))
    inspection -> message
  }

  private def noBuildFileContract(profile: Profile): SbtSemanticContract = {
    if (!profile.isSbt2) return SbtSemanticContract(events = Vector.empty)

    val scenarioId = "project-no-build-file"
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val ownership = compilerFlow(buildId)
    val compiler = s"Scala compiler [$scenarioId]"
    val start = ExpectedSemanticEvent(s"$scenarioId-start", CompilationStarted,
      "compiler" -> exact(compiler),
      "flowId" -> ownership)
    val problemType = inspectionType(s"$scenarioId-problem-type")
    val detail = ExpectedSemanticEvent(s"$scenarioId-detail", BuildLogMessage,
      "status" -> exact("WARNING"),
      "flowId" -> ownership,
      "text" -> exact("[warn] there was 1 deprecation warning; re-run with -deprecation for details"))
    val count = ExpectedSemanticEvent(s"$scenarioId-count", BuildLogMessage,
      "status" -> exact("WARNING"),
      "flowId" -> ownership,
      "text" -> exact("[warn] one warning found"))
    val finish = ExpectedSemanticEvent(s"$scenarioId-finish", CompilationFinished,
      "compiler" -> exact(compiler),
      "flowId" -> ownership)
    val ordered = Vector(start, problemType, detail, count, finish)

    SbtSemanticContract(
      events = ordered,
      happensBefore = chainEdges(ordered.map(_.id)),
      lifecycles = Vector(SemanticLifecycleRule.compilation(
        scenarioId,
        start.id.value,
        Seq(detail.id.value, count.id.value),
        finish.id.value,
        ownership
      ))
    )
  }

  private def inputsContract(profile: Profile): SbtSemanticContract =
    SbtSemanticContract(
      events = Vector.empty,
      // A direct SBT 2 input task emits only its raw task summary, so there is no service-message anchor.
      plainOutput = if (profile.isSbt2)
        PlainOutputContract.Patterns(Vector(SbtTaskSummary))
      else PlainOutputContract.RejectAll
    )

  private def assemble(part: CompilationPart, summaryCount: Int): SbtSemanticContract =
    SbtSemanticContract(
      events = part.events,
      happensBefore = part.edges,
      lifecycles = Vector(part.lifecycle),
      optionalGroups = part.optionalGroups,
      plainOutput = summaryContract(summaryCount)
    )

  private def inspectionType(id: String): ExpectedSemanticEvent =
    ExpectedSemanticEvent(id, InspectionType,
      "id" -> exact("SbtCompileProblem"),
      "name" -> exact("sbt compile problem"),
      "description" -> exact("Compile problems"),
      "category" -> exact("Compile problems"))

  private def compilerBridgeGroup(
    owner: String,
    ownership: SemanticValuePattern,
    after: SemanticEventId,
    before: SemanticEventId
  ): OptionalSemanticEventGroup = {
    val announcement = ExpectedSemanticEvent(s"$owner-bridge-announcement", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "flowId" -> ownership,
      "text" -> compilerBridgeAnnouncement)
    val completion = ExpectedSemanticEvent(s"$owner-bridge-completion", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "flowId" -> ownership,
      "text" -> compilerBridgeCompletion)
    OptionalSemanticEventGroup(
      s"$owner-compiler-bridge",
      Vector(announcement, completion),
      happensBefore = Set(
        HappensBefore(after, announcement.id),
        HappensBefore(announcement.id, completion.id),
        HappensBefore(completion.id, before)
      ),
      ownership = Some(ownership)
    )
  }

  private def compilerFlow(buildId: SemanticBindingKey): SemanticValuePattern =
    embedded("", buildId, ":compile:compiler")

  private def chainEdges(ids: Vector[SemanticEventId]): Set[HappensBefore] =
    ids.sliding(2).collect { case Vector(before, after) => HappensBefore(before, after) }.toSet

  private def summaryContract(count: Int): PlainOutputContract =
    if (count == 0) PlainOutputContract.RejectAll
    else PlainOutputContract.Patterns(Vector.fill(count)(AfterServiceMessages(SbtTaskSummary)))
}
