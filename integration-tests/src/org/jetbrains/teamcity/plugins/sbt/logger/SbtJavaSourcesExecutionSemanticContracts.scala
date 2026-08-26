package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtJavaSourcesExecutionSemanticContracts {
  import ObservedServiceMessageKind.*
  import PlainOutputContract.*
  import PlainOutputPattern.*
  import RawSbtDebugKind.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*
  import StructuredSbtDebugKind.*

  private val ScenarioId = "java-sources-compile-run"
  private val CompilerName = s"Scala compiler [$ScenarioId]"

  private final case class Profile(
    id: String,
    jdkMajor: Int,
    oldSbt1: Boolean,
    sbt2: Boolean,
    classesSuffix: String,
    packageSuffix: String,
    artifactFile: String,
    libraryFiles: Vector[String],
    generatedClasses: Vector[String]
  )

  private val Profiles = Vector(
    Profile(
      "sbt-1.4-jdk8",
      jdkMajor = 8,
      oldSbt1 = true,
      sbt2 = false,
      "/target/scala-2.13/classes",
      "/target/scala-2.13/java-sources-compile-run_2.13-0.1.0-SNAPSHOT.jar",
      "java-sources-compile-run_2.13-0.1.0-SNAPSHOT.jar",
      Vector("scala-library-2.13.18.jar"),
      Vector("HelloWorld.class", "HelloScala$.class", "HelloScala.class")
    ),
    Profile(
      "sbt-1-jdk8",
      jdkMajor = 8,
      oldSbt1 = false,
      sbt2 = false,
      "/target/scala-2.13/classes",
      "/target/scala-2.13/java-sources-compile-run_2.13-0.1.0-SNAPSHOT.jar",
      "java-sources-compile-run_2.13-0.1.0-SNAPSHOT.jar",
      Vector("scala-library-2.13.18.jar"),
      Vector("HelloWorld.class", "HelloScala$.class", "HelloScala.class")
    ),
    Profile(
      "sbt-1-jdk17",
      jdkMajor = 17,
      oldSbt1 = false,
      sbt2 = false,
      "/target/scala-2.13/classes",
      "/target/scala-2.13/java-sources-compile-run_2.13-0.1.0-SNAPSHOT.jar",
      "java-sources-compile-run_2.13-0.1.0-SNAPSHOT.jar",
      Vector("scala-library-2.13.18.jar"),
      Vector("HelloWorld.class", "HelloScala$.class", "HelloScala.class")
    ),
    Profile(
      "sbt-2-jdk17",
      jdkMajor = 17,
      oldSbt1 = false,
      sbt2 = true,
      "/target/out/jvm/scala-3.8.4/java-sources-compile-run/classes",
      "/target/out/jvm/scala-3.8.4/java-sources-compile-run/java-sources-compile-run_3-0.1.0-SNAPSHOT.jar",
      "java-sources-compile-run_3-0.1.0-SNAPSHOT.jar",
      Vector("scala3-library_3-3.8.4.jar", "scala-library.jar"),
      Vector(
        "HelloWorld.class",
        "HelloScala$.class",
        "HelloScala.class",
        "HelloWorld.tasty",
        "HelloScala$.tasty",
        "HelloScala.tasty"
      )
    )
  )
  private val ProfilesById = Profiles.map(profile => profile.id -> profile).toMap

  lazy val CompileAndRun: SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = ExactTranscript,
    runtimeProfileOverrides = Profiles.map { profile =>
      profile.id -> Hybrid(contract(profile), plainOutput(profile))
    }.toMap
  )

  private[logger] def semanticContractFor(runtimeProfile: String): SbtSemanticContract =
    contract(profile(runtimeProfile))

  private[logger] def plainOutputContractFor(runtimeProfile: String): PlainOutputContract =
    plainOutput(profile(runtimeProfile))

  private def profile(runtimeProfile: String): Profile = ProfilesById.getOrElse(
    runtimeProfile,
    throw new IllegalArgumentException(s"No Java compile/run semantic profile for '$runtimeProfile'.")
  )

  private def contract(profile: Profile): SbtSemanticContract = {
    val buildId = SemanticBindingKey.buildId(s"$ScenarioId-build")
    val workspace = SemanticBindingKey.path(s"$ScenarioId-workspace")
    val dependencyFlow = embedded("", buildId, ":global:dependency")
    val compilerOwnership = embedded("", buildId, ":compile:compiler")
    val incOptionsFlow = embedded("", buildId, ":compile:general:incOptions")
    val packageFlow = embedded("", buildId, ":compile:general:packageBin")
    val generalFlow = embedded("", buildId, ":compile:general:general")

    val dependency = structuredMessages(
      "dependency",
      Vector(DependencyCheck, DependencyUpdate, DependencyDone),
      dependencyFlow
    )
    val initialIncOptions = if (profile.oldSbt1) Vector.empty else structuredMessages(
      "initial-inc",
      Vector(CreatedClassFileManager, AboutToDeleteClassFiles, BackupClassFiles),
      incOptionsFlow
    )
    val firstStart = compilationBoundary("first-compile-start", CompilationStarted, 1)
    val firstCompilerPrefix = structuredMessages(
      "first-compile",
      Vector(
        IncrementalHeader,
        IncrementalCompile,
        PreviousStamps,
        CurrentSources,
        InitialChanges,
        FullCompilation,
        InvalidatedSources
      ),
      compilerOwnership
    )
    val firstMiddle = structuredMessages(
      "first-middle",
      Vector(CreatedClassFileManager),
      incOptionsFlow
    ) ++ structuredMessages(
      "first-compile-middle",
      Vector(InitialIncludedNodes, RecompileAllSources),
      compilerOwnership
    ) ++ structuredMessages(
      "first-middle-inc",
      Vector(AboutToDeleteClassFiles, BackupClassFiles),
      incOptionsFlow
    ) ++ structuredMessages(
      "first-compile-cycle",
      Vector(CompilationCycle),
      compilerOwnership
    )
    val compileInfo = normalMessage(
      "first-compile-info",
      compilerOwnership,
      embedded("[info] compiling 1 Scala source and 1 Java source to ", workspace, profile.classesSuffix + " ...")
    )
    val compilerBodyKinds = Vector(
      CompilerBridgeRetrieval,
      CachedCompiler,
      CompilerArguments,
      ScalaCompilationTiming
    ) ++ Option.when(!profile.oldSbt1 && !profile.sbt2)(JavaCompilerArguments) ++ Vector(
      JavacInvocation,
      JavaCompilationTiming
    ) ++ Option.when(!profile.oldSbt1)(JavaClassfileParsing) ++ Vector(
      JavaAnalysisTiming,
      JavaCompilationAndAnalysisTiming
    )
    val compilerBody = structuredMessages("first-compiler-body", compilerBodyKinds, compilerOwnership)
    val done = normalMessage("first-compile-done", compilerOwnership, exact("[info] done compiling"))
    val generated = normalMessage(
      "first-generated-classes",
      incOptionsFlow,
      exact("[debug] Registering generated classes:\n" + profile.generatedClasses.map(name => s"[debug] \t$name").mkString("\n"))
    )
    val cleanup = structuredMessages(
      "first-cleanup",
      Vector(RemoveTemporaryDirectory),
      incOptionsFlow
    )
    val wroteClasses = if (profile.sbt2)
      structuredMessages("first-wrote", Vector(WroteProducts), compilerOwnership)
    else Vector.empty
    val firstFinish = compilationBoundary("first-compile-finish", CompilationFinished, 1)
    val firstCompilation = Vector(firstStart) ++ firstCompilerPrefix ++ firstMiddle ++ Vector(compileInfo) ++
      compilerBody ++ Vector(done, generated) ++ cleanup ++ wroteClasses ++ Vector(firstFinish)

    val secondCompilation = if (profile.sbt2) Vector.empty else {
      val start = compilationBoundary("second-compile-start", CompilationStarted, 2)
      val compilerMessages = structuredMessages(
        "second-compile",
        Vector(IncrementalHeader, IncrementalCompile, PreviousStamps, CurrentSources, InitialChanges, NoChanges),
        compilerOwnership
      )
      val incMessages = structuredMessages(
        "second-inc",
        Vector(CreatedClassFileManager, RemoveTemporaryDirectory),
        incOptionsFlow
      )
      val finish = compilationBoundary("second-compile-finish", CompilationFinished, 2)
      val copyResources = normalMessage(
        "copy-resources",
        embedded("", buildId, ":compile:general:copyResources"),
        exact("[debug] Copy resource mappings: \n[debug] \t")
      )
      Vector(copyResources, start) ++ compilerMessages ++ incMessages ++ Vector(finish)
    }

    val packaging = Vector(
      normalMessage(
        "package-start",
        packageFlow,
        embedded("[debug] Packaging ", workspace, profile.packageSuffix + " ...")
      ),
      structuredMessage("package-inputs", PackageInputMappings, packageFlow),
      normalMessage("package-done", packageFlow, exact("[debug] Done packaging."))
    ) ++ Option.when(profile.sbt2)(structuredMessage("package-wrote", WrotePackage, packageFlow))

    val sbt1_4Run = if (!profile.oldSbt1) Vector.empty else Vector(
      normalMessage("run-start", generalFlow, exact("[info] running com.jetbrains.sbt.test.HelloWorld ")),
      normalMessage("run-wait", generalFlow,
        exact("[debug] Waiting for threads to exit or System.exit to be called.")),
      structuredMessage("run-classpath", SbtRunClasspath, generalFlow),
      normalMessage("run-thread-wait", generalFlow,
        exact("[debug] Waiting for thread run-main-0 to terminate.")),
      normalMessage("run-thread-exit", generalFlow, exact("[debug] \tThread run-main-0 exited.")),
      normalMessage("run-interrupt", generalFlow,
        exact("[debug] Interrupting remaining threads (should be all daemons).")),
      normalMessage("run-complete", generalFlow, exact("[debug] Sandboxed run complete..")),
      normalMessage("run-exit", generalFlow, exact("[debug] Exited with code 0"))
    )

    val events = dependency ++ initialIncOptions ++ firstCompilation ++ secondCompilation ++ packaging ++ sbt1_4Run
    val firstCompilerMembers = compilerMemberIds(firstCompilation, compilerOwnership)
    val lifecycles = Vector(SemanticLifecycleRule.compilation(
      "first-java-compilation",
      firstStart.id.value,
      firstCompilerMembers.map(_.value),
      firstFinish.id.value,
      compilerOwnership
    )) ++ Option.when(!profile.sbt2) {
      SemanticLifecycleRule.compilation(
        "second-java-compilation",
        "second-compile-start",
        compilerMemberIds(secondCompilation, compilerOwnership).map(_.value),
        "second-compile-finish",
        compilerOwnership
      )
    }

    SbtSemanticContract(
      events = events,
      happensBefore = chainEdges(events.map(_.id)),
      lifecycles = lifecycles,
      optionalGroups = Vector(compilerBridgeGroup(
        compilerOwnership,
        compileInfo.id,
        compilerBody.head.id
      )),
      plainOutput = DelegatedToHybrid
    )
  }

  private def plainOutput(profile: Profile): PlainOutputContract = {
    val programOutput = Vector(
      PlainOutputPattern.Exact("Hello, World!"),
      JavaRuntimeVersion(profile.jdkMajor),
      JavaRuntimeHome(profile.jdkMajor == 8)
    )
    if (profile.oldSbt1) Patterns(programOutput)
    else {
      val runOutput = Vector(
        PlainOutputPattern.Exact("[info] running com.jetbrains.sbt.test.HelloWorld "),
        PlainOutputPattern.Exact("[debug]   Classpath:"),
        JavaRunClasspathEntry(profile.artifactFile, jobScoped = true)
      ) ++ profile.libraryFiles.map(file => JavaRunClasspathEntry(file, jobScoped = false)) ++ programOutput
      val all = if (profile.sbt2) Vector(
        BeforeServiceMessages(SbtDebug(CommandExecution)),
        BeforeServiceMessages(SbtDebug(TaskEvaluation)),
        BeforeServiceMessages(SbtDebug(TaskRun)),
        SbtTaskSummary,
        SbtDebug(CommandExecution),
        SbtDebug(TaskEvaluation),
        SbtDebug(TaskRun)
      ) ++ runOutput ++ Vector(SbtTaskSummary) else runOutput
      Patterns(all)
    }
  }

  private def compilationBoundary(
    id: String,
    kind: ObservedServiceMessageKind,
    occurrence: Int
  ): ExpectedSemanticEvent = ExpectedSemanticEvent.occurrence(
    id,
    kind,
    occurrence,
    "compiler" -> exact(CompilerName)
  )

  private def structuredMessages(
    prefix: String,
    kinds: Vector[StructuredSbtDebugKind],
    flow: SemanticValuePattern
  ): Vector[ExpectedSemanticEvent] = kinds.zipWithIndex.map { case (kind, index) =>
    structuredMessage(s"$prefix-${index + 1}-${kind.id}", kind, flow)
  }

  private def structuredMessage(
    id: String,
    kind: StructuredSbtDebugKind,
    flow: SemanticValuePattern
  ): ExpectedSemanticEvent = normalMessage(id, flow, structuredSbtDebug(kind))

  private def normalMessage(
    id: String,
    flow: SemanticValuePattern,
    text: SemanticValuePattern
  ): ExpectedSemanticEvent = ExpectedSemanticEvent(id, BuildLogMessage,
    "status" -> exact("NORMAL"),
    "flowId" -> flow,
    "text" -> text)

  private def compilerMemberIds(
    events: Vector[ExpectedSemanticEvent],
    ownership: SemanticValuePattern
  ): Vector[SemanticEventId] = events.collect {
    case event if event.kind == BuildLogMessage && event.attributes.contains("flowId" -> ownership) => event.id
  }

  private def compilerBridgeGroup(
    ownership: SemanticValuePattern,
    before: SemanticEventId,
    after: SemanticEventId
  ): OptionalSemanticEventGroup = {
    val announcement = normalMessage(
      "compiler-bridge-announcement",
      ownership,
      compilerBridgeAnnouncement
    )
    val completion = normalMessage(
      "compiler-bridge-completion",
      ownership,
      compilerBridgeCompletion
    )
    OptionalSemanticEventGroup(
      "java-compiler-bridge",
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
