package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtDependencyDetailedOutcomesSemanticContracts {
  import DependencyResourceOutcome.*
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  private val Sbt1Profile = "sbt-1-jdk17"
  private val Sbt2Profile = "sbt-2-jdk17"

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
