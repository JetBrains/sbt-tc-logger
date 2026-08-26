package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtDependencyDetailedOutcomesSemanticContracts {
  import DependencyResourceOutcome.*
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import RawSbtDebugKind.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*
  import StructuredSbtDebugKind.*

  private val Sbt14Profile = "sbt-1.4-jdk8"
  private val Sbt1Jdk8Profile = "sbt-1-jdk8"
  private val Sbt1Profile = "sbt-1-jdk17"
  private val Sbt2Profile = "sbt-2-jdk17"
  private val AllProfiles = Vector(Sbt14Profile, Sbt1Jdk8Profile, Sbt1Profile, Sbt2Profile)
  private val DetailedProfiles = Vector(Sbt1Profile, Sbt2Profile)

  lazy val DefaultUpdate: SbtOutputVerificationSelection = selections(AllProfiles) { profile =>
    Semantic(defaultUpdateContract(isSbt2(profile)))
  }

  lazy val DefaultUpdateFailure: SbtOutputVerificationSelection = selections(AllProfiles) { profile =>
    Semantic(defaultUpdateFailureContract(isSbt2(profile)))
  }

  lazy val Failure: SbtOutputVerificationSelection = selections(DetailedProfiles) { profile =>
    Hybrid(detailedFailureContract(isSbt2(profile)), FailurePlainOutput)
  }

  lazy val DebugDisabled: SbtOutputVerificationSelection = selections(DetailedProfiles) { profile =>
    Hybrid(debugDisabledContract, debugPlainOutput(isSbt2(profile)))
  }

  lazy val PreserveConsoleDisabled: SbtOutputVerificationSelection = selections(DetailedProfiles) { _ =>
    Hybrid(emptyHybridContract, PlainOutputContract.Patterns(Vector(SbtTaskSummary)))
  }

  /** SBT 2 remains the exact end-to-end canary while both profile shapes have focused semantic coverage. */
  lazy val Success: SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = ExactTranscript,
    runtimeProfileOverrides = Map(
      Sbt1Profile -> Semantic(semanticContractFor(Sbt1Profile)),
      Sbt2Profile -> ExactTranscript
    )
  )

  private val DependencyFlow = "teamcity-sbt-dependency-resolution"
  private val Project = "dependency-detailed-outcomes"
  private val Configuration = "global"
  private val MavenCentral = "https://repo1.maven.org/maven2/"
  private val AllowedOutcomes = Set(LocalCacheHit, Downloaded)
  private val MissingCoordinate =
    "org.example.teamcity.logger:deliberately-missing-update-dependency_2.13:0.0.0"
  private val MissingPom = MavenCentral +
    "org/example/teamcity/logger/deliberately-missing-update-dependency_2.13/0.0.0/" +
    "deliberately-missing-update-dependency_2.13-0.0.0.pom"
  private val FailurePlainOutput =
    PlainOutputContract.Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))

  private final case class Profile(
    firstRunUrls: Vector[String],
    firstRunCacheHits: Int,
    cachedRunUrls: Vector[String]
  )

  private final case class BlockPart(
    events: Vector[ExpectedSemanticEvent],
    edges: Set[HappensBefore],
    lifecycle: SemanticLifecycleRule,
    open: SemanticEventId,
    close: SemanticEventId
  )

  private final case class ResolutionFailurePart(
    warnings: Vector[ExpectedSemanticEvent],
    errors: Vector[ExpectedSemanticEvent]
  )

  private val Sbt1 = Profile(
    firstRunUrls = Vector(
      "org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.pom",
      "org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.pom",
      "org/scala-lang/scala-compiler/2.13.18/scala-compiler-2.13.18.pom",
      "org/jline/jline/3.29.0/jline-3.29.0.pom",
      "org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.pom",
      "io/github/java-diff-utils/java-diff-utils/4.16/java-diff-utils-4.16.pom",
      "org/jline/jline-parent/3.29.0/jline-parent-3.29.0.pom",
      "io/github/java-diff-utils/java-diff-utils-parent/4.16/java-diff-utils-parent-4.16.pom",
      "io/github/java-diff-utils/java-diff-utils/4.16/java-diff-utils-4.16.jar",
      "org/jline/jline/3.29.0/jline-3.29.0-jdk8.jar",
      "org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar",
      "org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar",
      "org/scala-lang/scala-compiler/2.13.18/scala-compiler-2.13.18.jar"
    ).map(MavenCentral + _),
    firstRunCacheHits = 13,
    cachedRunUrls = Vector(
      "org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar",
      "org/jline/jline/3.29.0/jline-3.29.0-jdk8.jar",
      "org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar",
      "org/scala-lang/scala-compiler/2.13.18/scala-compiler-2.13.18.jar",
      "io/github/java-diff-utils/java-diff-utils/4.16/java-diff-utils-4.16.jar"
    ).map(MavenCentral + _)
  )

  private val Sbt2 = Profile(
    firstRunUrls = Vector(
      "org/scala-lang/scala-library/2.12.20/scala-library-2.12.20.pom",
      "org/scala-lang/scala-library/2.12.20/scala-library-2.12.20.pom",
      "org/scala-lang/scala-compiler/2.12.20/scala-compiler-2.12.20.pom",
      "org/scala-lang/modules/scala-xml_2.12/2.3.0/scala-xml_2.12-2.3.0.pom",
      "org/scala-lang/scala-reflect/2.12.20/scala-reflect-2.12.20.pom",
      "jline/jline/2.14.6/jline-2.14.6.pom",
      "org/sonatype/oss/oss-parent/9/oss-parent-9.pom",
      "jline/jline/2.14.6/jline-2.14.6.jar",
      "org/scala-lang/scala-reflect/2.12.20/scala-reflect-2.12.20.jar",
      "org/scala-lang/scala-library/2.12.20/scala-library-2.12.20.jar",
      "org/scala-lang/scala-compiler/2.12.20/scala-compiler-2.12.20.jar",
      "org/scala-lang/modules/scala-xml_2.12/2.3.0/scala-xml_2.12-2.3.0.jar"
    ).map(MavenCentral + _),
    firstRunCacheHits = 12,
    cachedRunUrls = Vector(
      "jline/jline/2.14.6/jline-2.14.6.jar",
      "org/scala-lang/scala-compiler/2.12.20/scala-compiler-2.12.20.jar",
      "org/scala-lang/scala-library/2.12.20/scala-library-2.12.20.jar",
      "org/scala-lang/modules/scala-xml_2.12/2.3.0/scala-xml_2.12-2.3.0.jar",
      "org/scala-lang/scala-reflect/2.12.20/scala-reflect-2.12.20.jar"
    ).map(MavenCentral + _)
  )

  private[logger] def semanticContractFor(runtimeProfile: String): SbtSemanticContract = {
    val profile = runtimeProfile match {
      case Sbt1Profile => Sbt1
      case Sbt2Profile => Sbt2
      case other => throw new IllegalArgumentException(s"No detailed-dependency semantic profile for '$other'.")
    }
    contract(profile)
  }

  private[logger] def defaultUpdateContractFor(runtimeProfile: String): SbtSemanticContract =
    defaultUpdateContract(isSbt2(knownProfile(runtimeProfile, AllProfiles, "default update")))

  private[logger] def defaultUpdateFailureContractFor(runtimeProfile: String): SbtSemanticContract =
    defaultUpdateFailureContract(isSbt2(knownProfile(runtimeProfile, AllProfiles, "default update failure")))

  private[logger] def detailedFailureContractFor(runtimeProfile: String): SbtSemanticContract =
    detailedFailureContract(isSbt2(knownProfile(runtimeProfile, DetailedProfiles, "detailed dependency failure")))

  private[logger] def debugDisabledContractFor(runtimeProfile: String): SbtSemanticContract = {
    knownProfile(runtimeProfile, DetailedProfiles, "detailed dependency debug")
    debugDisabledContract
  }

  private def selections(
    profiles: Vector[String]
  )(mode: String => SbtOutputVerification): SbtOutputVerificationSelection =
    SbtOutputVerificationSelection(
      defaultMode = ExactTranscript,
      runtimeProfileOverrides = profiles.map(profile => profile -> mode(profile)).toMap
    )

  private def defaultUpdateContract(sbt2: Boolean): SbtSemanticContract =
    SbtSemanticContract(
      events = Vector.empty,
      plainOutput = if (sbt2)
        PlainOutputContract.Patterns(Vector(SbtTaskSummary))
      else PlainOutputContract.RejectAll
    )

  private def defaultUpdateFailureContract(sbt2: Boolean): SbtSemanticContract = {
    val failure = resolutionFailurePart("dependency-update-failure-default", sbt2)
    val events = failure.warnings ++ failure.errors
    SbtSemanticContract(
      events = events,
      happensBefore = chainEdges(events.map(_.id)),
      plainOutput = if (sbt2)
        PlainOutputContract.Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
      else PlainOutputContract.RejectAll
    )
  }

  private def detailedFailureContract(sbt2: Boolean): SbtSemanticContract = {
    val scenarioId = "dependency-detailed-failure"
    val failure = resolutionFailurePart(scenarioId, sbt2)
    val open = ExpectedSemanticEvent("failure-open", BlockOpened,
      "name" -> exact("Dependency resolution"))
    val library = ExpectedSemanticEvent("failure-library", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> dependencyResource(
        scenarioId,
        Configuration,
        MavenCentral + "org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.pom",
        AllowedOutcomes
      ))
    val failedPom = ExpectedSemanticEvent("failure-missing-pom", BuildLogMessage,
      "status" -> exact("WARNING"),
      "text" -> dependencyResource(
        scenarioId,
        Configuration,
        MissingPom,
        Set(FailedDownloadAttempt)
      ))
    val failedSha = ExpectedSemanticEvent("failure-missing-sha1", BuildLogMessage,
      "status" -> exact("WARNING"),
      "text" -> dependencyResource(
        scenarioId,
        Configuration,
        s"$MissingPom.sha1",
        Set(FailedDownloadAttempt)
      ))
    val summary = ExpectedSemanticEvent("failure-summary", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> dependencyResolutionSummary(
        localCacheHits = 1,
        downloads = 0,
        failedDownloadAttempts = 2,
        updateReportCacheHits = 0
      ))
    val close = ExpectedSemanticEvent("failure-close", BlockClosed,
      "name" -> exact("Dependency resolution"))
    val resources = Vector(library, failedPom, failedSha)
    val warnings = failure.warnings
    val errors = failure.errors
    val warningEdges = resources.map(resource => HappensBefore(resource.id, warnings.head.id)).toSet ++
      chainEdges(warnings.map(_.id)) + HappensBefore(warnings.last.id, summary.id)
    val errorEdges = Set(HappensBefore(close.id, errors.head.id)) ++ chainEdges(errors.map(_.id))

    SbtSemanticContract(
      events = Vector(open) ++ resources ++ warnings ++ Vector(summary, close) ++ errors,
      happensBefore = warningEdges ++ errorEdges,
      lifecycles = Vector(SemanticLifecycleRule.block(
        "failed-dependency-resolution",
        open.id.value,
        resources.map(_.id.value) :+ summary.id.value,
        close.id.value,
        exact(DependencyFlow)
      )),
      plainOutput = PlainOutputContract.DelegatedToHybrid
    )
  }

  private def resolutionFailurePart(scenarioId: String, sbt2: Boolean): ResolutionFailurePart = {
    val buildId = SemanticBindingKey.buildId(s"$scenarioId-build")
    val ivyHome = SemanticBindingKey.path(s"$scenarioId-ivy-home")
    val buildFlow = embedded("", buildId, ":global:dependency")
    val note = ExpectedSemanticEvent("resolve-warning-note", BuildLogMessage,
      "status" -> exact("WARNING"),
      "flowId" -> buildFlow,
      "text" -> exact("[warn] \n[warn] \tNote: Unresolved dependencies path:"))
    val sbt2Warnings = if (sbt2) Vector(
      ExpectedSemanticEvent("resolve-warning-coordinate", BuildLogMessage,
        "status" -> exact("WARNING"),
        "flowId" -> buildFlow,
        "text" -> embedded(
          "[warn] \t\torg.example.teamcity.logger:deliberately-missing-update-dependency_2.13:0.0.0 (",
          SemanticBindingKey.path(s"$scenarioId-workspace"),
          "/build.sbt#L4-4)"
        )),
      ExpectedSemanticEvent("resolve-warning-project", BuildLogMessage,
        "status" -> exact("WARNING"),
        "flowId" -> buildFlow,
        "text" -> exact(s"[warn] \t\t  +- default:${scenarioId}_2.13:0.1.0-SNAPSHOT"))
    ) else Vector.empty
    val full = ExpectedSemanticEvent("resolve-error-full", BuildLogMessage,
      "status" -> exact("ERROR"),
      "flowId" -> buildFlow,
      "text" -> dependencyResolveFailure(
        MissingCoordinate,
        MissingPom,
        ivyHome,
        taskScoped = false
      ))
    val task = ExpectedSemanticEvent("resolve-error-task", BuildLogMessage,
      "status" -> exact("ERROR"),
      "flowId" -> buildFlow,
      "text" -> dependencyResolveFailure(
        MissingCoordinate,
        MissingPom,
        ivyHome,
        taskScoped = true
      ))
    ResolutionFailurePart(Vector(note) ++ sbt2Warnings, Vector(full, task))
  }

  private lazy val debugDisabledContract: SbtSemanticContract = {
    val buildId = SemanticBindingKey.buildId("dependency-detailed-debug-build")
    val flow = embedded("", buildId, ":global:dependency")
    val events = Vector(DependencyCheck, DependencyUpdate, DependencyDone).map { kind =>
      ExpectedSemanticEvent(s"dependency-debug-${kind.id}", BuildLogMessage,
        "status" -> exact("NORMAL"),
        "flowId" -> flow,
        "text" -> structuredSbtDebug(kind))
    }
    SbtSemanticContract(
      events = events,
      happensBefore = chainEdges(events.map(_.id)),
      plainOutput = PlainOutputContract.DelegatedToHybrid
    )
  }

  private val emptyHybridContract = SbtSemanticContract(
    events = Vector.empty,
    plainOutput = PlainOutputContract.DelegatedToHybrid
  )

  private def debugPlainOutput(sbt2: Boolean): PlainOutputContract =
    if (sbt2) PlainOutputContract.Patterns(Vector(
      BeforeServiceMessages(SbtDebug(CommandExecution)),
      BeforeServiceMessages(SbtDebug(TaskEvaluation)),
      BeforeServiceMessages(SbtDebug(TaskRun)),
      AfterServiceMessages(SbtTaskSummary)
    ))
    else PlainOutputContract.RejectAll

  private def knownProfile(profile: String, profiles: Vector[String], description: String): String = {
    if (!profiles.contains(profile)) {
      throw new IllegalArgumentException(s"No $description semantic profile for '$profile'.")
    }
    profile
  }

  private def isSbt2(profile: String): Boolean = profile == Sbt2Profile

  private def chainEdges(ids: Vector[SemanticEventId]): Set[HappensBefore] =
    ids.sliding(2).collect { case Vector(before, after) => HappensBefore(before, after) }.toSet

  private def contract(profile: Profile): SbtSemanticContract = {
    val first = blockPart("first", profile.firstRunUrls, profile.firstRunCacheHits)
    val second = blockPart("second", profile.cachedRunUrls, profile.cachedRunUrls.size)
    SbtSemanticContract(
      events = first.events ++ second.events,
      happensBefore = first.edges ++ second.edges + HappensBefore(first.close, second.open),
      lifecycles = Vector(first.lifecycle, second.lifecycle),
      // SBT owns when its task summaries are printed; only their exact count and finite shape are contractual.
      plainOutput = PlainOutputContract.Patterns(Vector(SbtTaskSummary, SbtTaskSummary))
    )
  }

  private def blockPart(
    label: String,
    urls: Vector[String],
    localCacheHits: Int
  ): BlockPart = {
    val open = ExpectedSemanticEvent(s"$label-open", BlockOpened, "name" -> exact("Dependency resolution"))
    val resources = urls.distinct.zipWithIndex.map { case (url, index) =>
      ExpectedSemanticEvent.repeated(
        s"$label-resource-${index + 1}",
        BuildLogMessage,
        multiplicity = urls.count(_ == url),
        "status" -> exact("NORMAL"),
        "text" -> dependencyResource(Project, Configuration, url, AllowedOutcomes)
      )
    }
    val duration = SemanticBindingKey.duration(s"$label-summary-duration")
    val summary = ExpectedSemanticEvent(s"$label-summary", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded(
        "Dependency resolution finished in ",
        duration,
        s" ms: $localCacheHits local cache hits, 0 downloads, 0 failed download attempts, 0 sbt update report cache hits"
      ))
    val close = ExpectedSemanticEvent(s"$label-close", BlockClosed, "name" -> exact("Dependency resolution"))
    val resourceIds = resources.map(_.id)
    BlockPart(
      events = Vector(open) ++ resources ++ Vector(summary, close),
      edges = resourceIds.map(resource => HappensBefore(resource, summary.id)).toSet,
      lifecycle = SemanticLifecycleRule.block(
        s"$label-dependency-resolution",
        open.id.value,
        resourceIds.map(_.value) :+ summary.id.value,
        close.id.value,
        exact(DependencyFlow)
      ),
      open = open.id,
      close = close.id
    )
  }
}
