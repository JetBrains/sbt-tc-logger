package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtMultiProjectSemanticContracts {
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*
  import StructuredSbtDebugKind.*

  val Failure: SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = Semantic(nonDebugContract(CompilerFlavor.Scala2, PlainOutputContract.RejectAll)),
    runtimeProfileOverrides = Map(
      "sbt-2-jdk17" -> Semantic(nonDebugContract(
        CompilerFlavor.Scala3,
        PlainOutputContract.Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
      ))
    )
  )

  val FailureDebug: SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = Semantic(debugContract(DebugProfile.ModernSbt1, PlainOutputContract.RejectAll)),
    runtimeProfileOverrides = Map(
      "sbt-1.4-jdk8" -> Semantic(debugContract(DebugProfile.Sbt1_4, PlainOutputContract.RejectAll)),
      "sbt-2-jdk17" -> Hybrid(
        debugContract(DebugProfile.Sbt2, PlainOutputContract.DelegatedToHybrid),
        PlainOutputContract.Patterns(Vector(
          BeforeServiceMessages(SbtDebug(RawSbtDebugKind.CommandExecution)),
          BeforeServiceMessages(SbtDebug(RawSbtDebugKind.TaskEvaluation)),
          BeforeServiceMessages(SbtDebug(RawSbtDebugKind.TaskRun)),
          AfterServiceMessages(SbtTaskSummary)
        ))
      )
    )
  )

  private enum CompilerFlavor(
    val problem: String,
    val caret: String
  ) {
    case Scala2 extends CompilerFlavor("invalid literal number", " " * 34 + "^")
    case Scala3 extends CompilerFlavor("value println is not a member of Int", " " * 37 + "^")
  }

  private lazy val DependencyDebug = Vector(DependencyCheck, DependencyUpdate, DependencyDone)
  private lazy val RootCompilerDebug = Vector(
    IncrementalHeader,
    IncrementalCompile,
    PreviousStamps,
    CurrentSources,
    InitialChanges,
    FullCompilation
  )
  private lazy val ProjectCompilerDebug = RootCompilerDebug ++ Vector(
    InvalidatedSources,
    InitialIncludedNodes,
    RecompileAllSources,
    CompilationCycle,
    CompilerBridgeRetrieval,
    CachedCompiler,
    CompilerArguments,
    CompilationFailed
  )
  private lazy val RootIncOptionsDebug = Vector(CreatedClassFileManager, RemoveTemporaryDirectory)
  private lazy val ProjectIncOptionsDebug = Vector(
    CreatedClassFileManager,
    AboutToDeleteClassFiles,
    BackupClassFiles,
    RollbackClassFiles,
    RemoveGeneratedClasses,
    RestoreClassFiles,
    RemoveTemporaryDirectory
  )
  private lazy val IncOptionsPrelude = Vector(
    CreatedClassFileManager,
    AboutToDeleteClassFiles,
    BackupClassFiles
  )
  private lazy val ModernRootIncOptionsDebug = IncOptionsPrelude ++ RootIncOptionsDebug
  private lazy val ModernProjectIncOptionsDebug = IncOptionsPrelude ++ ProjectIncOptionsDebug

  private enum DebugProfile(
    val compilerFlavor: CompilerFlavor,
    val rootCompilerDebug: Vector[StructuredSbtDebugKind],
    val rootIncOptionsDebug: Vector[StructuredSbtDebugKind],
    val projectIncOptionsDebug: Vector[StructuredSbtDebugKind]
  ) {
    case Sbt1_4 extends DebugProfile(
      CompilerFlavor.Scala2,
      RootCompilerDebug,
      RootIncOptionsDebug,
      ProjectIncOptionsDebug
    )
    case ModernSbt1 extends DebugProfile(
      CompilerFlavor.Scala2,
      RootCompilerDebug,
      ModernRootIncOptionsDebug,
      ModernProjectIncOptionsDebug
    )
    case Sbt2 extends DebugProfile(
      CompilerFlavor.Scala3,
      RootCompilerDebug :+ WroteProducts,
      ModernRootIncOptionsDebug,
      ModernProjectIncOptionsDebug
    )
  }

  private final case class ContractPart(
    events: Vector[ExpectedSemanticEvent],
    edges: Set[HappensBefore],
    lifecycle: SemanticLifecycleRule,
    optionalGroups: Vector[OptionalSemanticEventGroup],
    buildId: SemanticBindingKey
  )

  private def nonDebugContract(
    compilerFlavor: CompilerFlavor,
    plainOutput: PlainOutputContract
  ): SbtSemanticContract = {
    val sourceBase = SemanticBindingKey.path("multi-project-source-base")
    val project1 = projectPart(
      "project1", line = 4, compilerFlavor, debugProfile = None, sourceBase = sourceBase)
    val project2 = projectPart(
      "project2", line = 6, compilerFlavor, debugProfile = None, sourceBase = sourceBase)
    assemble(Vector(project1, project2), plainOutput)
  }

  private def debugContract(
    profile: DebugProfile,
    plainOutput: PlainOutputContract
  ): SbtSemanticContract = {
    val sourceBase = SemanticBindingKey.path("multi-project-source-base")
    val root = rootPart(profile)
    val project1 = projectPart(
      "project1", line = 4, profile.compilerFlavor, Some(profile), sourceBase = sourceBase)
    val project2 = projectPart(
      "project2", line = 6, profile.compilerFlavor, Some(profile), sourceBase = sourceBase)
    assemble(Vector(root, project1, project2), plainOutput)
  }

  private def assemble(parts: Vector[ContractPart], plainOutput: PlainOutputContract): SbtSemanticContract = {
    val distinctBuildIds = parts.indices.flatMap { first =>
      ((first + 1) until parts.size).map { second =>
        DistinctSemanticBindings(parts(first).buildId, parts(second).buildId)
      }
    }.toSet
    SbtSemanticContract(
      events = ExpectedSemanticEvent.repeated(
        "compile-problem-type",
        InspectionType,
        multiplicity = 2,
        "id" -> exact("SbtCompileProblem"),
        "name" -> exact("sbt compile problem"),
        "description" -> exact("Compile problems"),
        "category" -> exact("Compile problems")
      ) +: parts.flatMap(_.events),
      happensBefore = parts.flatMap(_.edges).toSet,
      lifecycles = parts.map(_.lifecycle),
      optionalGroups = parts.flatMap(_.optionalGroups),
      distinctBindings = distinctBuildIds,
      plainOutput = plainOutput
    )
  }

  private def rootPart(profile: DebugProfile): ContractPart = {
    val owner = "root"
    val buildId = SemanticBindingKey.buildId(s"$owner-build")
    val compilerDebug = structuredDebugEvents(owner, "compiler-debug", profile.rootCompilerDebug)
    val dependencyDebug = structuredDebugEvents(
      owner,
      "dependency-debug",
      DependencyDebug,
      Some(flow(buildId, ":global:dependency"))
    )
    val incOptionsDebug = structuredDebugEvents(
      owner,
      "inc-options-debug",
      profile.rootIncOptionsDebug,
      Some(flow(buildId, ":compile:general:incOptions"))
    )
    val start = ExpectedSemanticEvent(s"$owner-start", CompilationStarted,
      "compiler" -> exact("Scala compiler [root]"))
    val finish = ExpectedSemanticEvent(s"$owner-finish", CompilationFinished,
      "compiler" -> exact("Scala compiler [root]"))
    ContractPart(
      events = dependencyDebug ++ incOptionsDebug ++ Vector(start, finish) ++ compilerDebug,
      edges = Set.empty,
      lifecycle = SemanticLifecycleRule.compilation(
        owner,
        start.id.value,
        compilerDebug.map(_.id.value),
        finish.id.value,
        flow(buildId, ":compile:compiler")
      ),
      optionalGroups = Vector.empty,
      buildId = buildId
    )
  }

  private def projectPart(
    project: String,
    line: Int,
    compilerFlavor: CompilerFlavor,
    debugProfile: Option[DebugProfile],
    sourceBase: SemanticBindingKey
  ): ContractPart = {
    val buildId = SemanticBindingKey.buildId(s"$project-build")
    val outputPath = SemanticBindingKey.path(s"$project-output")
    val sourceSuffix = s"/$project/Hi${if (project == "project1") "Project1" else "Project2"}.scala"
    val start = ExpectedSemanticEvent(s"$project-start", CompilationStarted,
      "compiler" -> exact(s"Scala compiler [$project]"))
    val inspection = ExpectedSemanticEvent(s"$project-inspection", Inspection,
      "SEVERITY" -> exact("ERROR"),
      "line" -> exact(line.toString),
      "typeId" -> exact("SbtCompileProblem"),
      "message" -> exact(compilerFlavor.problem),
      "file" -> embedded("", sourceBase, sourceSuffix))
    val detailedError = ExpectedSemanticEvent(s"$project-detailed-error", BuildLogMessage,
      "status" -> exact("ERROR"),
      "text" -> embedded("[error] ", sourceBase, sourceSuffix + detailedErrorSuffix(project, line, compilerFlavor)))
    val errorCount = ExpectedSemanticEvent(s"$project-error-count", BuildLogMessage,
      "status" -> exact("ERROR"),
      "text" -> exact("[error] one error found"))
    val finish = ExpectedSemanticEvent(s"$project-finish", CompilationFinished,
      "compiler" -> exact(s"Scala compiler [$project]"))
    val taskFailure = ExpectedSemanticEvent(s"$project-task-failure", BuildLogMessage,
      "status" -> exact("ERROR"),
      "text" -> exact(s"[error] ($project / Compile / compileIncremental) Compilation failed"))

    val compilerDebug = debugProfile.toVector.flatMap(_ =>
      structuredDebugEvents(project, "compiler-debug", ProjectCompilerDebug)
    )
    val dependencyDebug = debugProfile.toVector.flatMap(_ => structuredDebugEvents(
      project,
      "dependency-debug",
      DependencyDebug,
      Some(flow(buildId, ":global:dependency"))
    ))
    val incOptionsDebug = debugProfile.toVector.flatMap(profile => structuredDebugEvents(
      project,
      "inc-options-debug",
      profile.projectIncOptionsDebug,
      Some(flow(buildId, ":compile:general:incOptions"))
    ))
    val compileInfo = debugProfile.map(_ => ExpectedSemanticEvent(s"$project-compile-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded("[info] compiling 1 Scala source to ", outputPath, " ..."))).toVector
    val lifecycleMembers = compilerDebug ++ compileInfo ++ Vector(detailedError, errorCount)
    val stableEdges = Set(
      HappensBefore(start.id, inspection.id),
      HappensBefore(inspection.id, detailedError.id),
      HappensBefore(detailedError.id, errorCount.id),
      HappensBefore(errorCount.id, finish.id),
      HappensBefore(finish.id, taskFailure.id)
    ) ++ compileInfo.map(info => HappensBefore(info.id, inspection.id))
    ContractPart(
      events = dependencyDebug ++ incOptionsDebug ++ Vector(start, inspection) ++ lifecycleMembers ++
        Vector(finish, taskFailure),
      edges = stableEdges,
      lifecycle = SemanticLifecycleRule.compilation(
        project,
        start.id.value,
        lifecycleMembers.map(_.id.value),
        finish.id.value,
        flow(buildId, ":compile:compiler")
      ),
      optionalGroups = debugProfile.map(_ => compilerBridgeGroup(project, buildId)).toVector,
      buildId = buildId
    )
  }

  private def structuredDebugEvents(
    owner: String,
    role: String,
    kinds: Vector[StructuredSbtDebugKind],
    explicitFlow: Option[SemanticValuePattern] = None
  ): Vector[ExpectedSemanticEvent] = kinds.groupBy(identity).toVector.sortBy(_._1.id).map { case (kind, occurrences) =>
    val attributes = Vector[(String, SemanticValuePattern)](
      "status" -> exact("NORMAL"),
      "text" -> structuredSbtDebug(kind)
    ) ++ explicitFlow.map("flowId" -> _)
    ExpectedSemanticEvent(
      SemanticEventId(s"$owner-$role-${kind.id}"),
      BuildLogMessage,
      attributes,
      multiplicity = occurrences.size
    )
  }

  private def compilerBridgeGroup(
    project: String,
    buildId: SemanticBindingKey
  ): OptionalSemanticEventGroup = {
    val announcement = ExpectedSemanticEvent(s"$project-bridge-announcement", BuildLogMessage,
      "status" -> exact("NORMAL"), "text" -> compilerBridgeAnnouncement)
    val completion = ExpectedSemanticEvent(s"$project-bridge-completion", BuildLogMessage,
      "status" -> exact("NORMAL"), "text" -> compilerBridgeCompletion)
    OptionalSemanticEventGroup(
      s"$project-compiler-bridge",
      Vector(announcement, completion),
      Set(
        HappensBefore(s"$project-compile-info", announcement.id.value),
        HappensBefore(announcement.id, completion.id),
        HappensBefore(completion.id.value, s"$project-compiler-debug-compiler-bridge-retrieval")
      ),
      Some(flow(buildId, ":compile:compiler"))
    )
  }

  private def flow(buildId: SemanticBindingKey, suffix: String): SemanticValuePattern =
    embedded("", buildId, suffix)

  private def detailedErrorSuffix(
    project: String,
    line: Int,
    flavor: CompilerFlavor
  ): String =
    s":$line: ${flavor.problem}\n" +
      s"[error]   def main(args: Array[String]) = ${if (project == "project1") "111" else "222"}println(" +
      s"\"Hi from ${if (project == "project1") "Project 1" else "Project 2"}!\")\n" +
      s"[error] ${flavor.caret}"
}
