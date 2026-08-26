package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtDependencyDetailedOutcomesSemanticContractTest {
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.SbtTaskSummary
  import SemanticValuePattern.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private val Sbt1Profile = "sbt-1-jdk17"
  private val Sbt2Profile = "sbt-2-jdk17"
  private val DependencyFlow = "teamcity-sbt-dependency-resolution"
  private val MavenCentral = "https://repo1.maven.org/maven2/"
  private val TaskSummary = "[success] Total time: 1.25 s, completed Aug 26, 2026, 12:00:00 PM"

  private final case class ExpectedProfile(
    runtimeProfile: String,
    firstRunPaths: Vector[String],
    cachedRunPaths: Vector[String]
  )

  private val Profiles = Vector(
    ExpectedProfile(
      Sbt1Profile,
      Vector(
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
      ),
      Vector(
        "org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar",
        "org/jline/jline/3.29.0/jline-3.29.0-jdk8.jar",
        "org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar",
        "org/scala-lang/scala-compiler/2.13.18/scala-compiler-2.13.18.jar",
        "io/github/java-diff-utils/java-diff-utils/4.16/java-diff-utils-4.16.jar"
      )
    ),
    ExpectedProfile(
      Sbt2Profile,
      Vector(
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
      ),
      Vector(
        "jline/jline/2.14.6/jline-2.14.6.jar",
        "org/scala-lang/scala-compiler/2.12.20/scala-compiler-2.12.20.jar",
        "org/scala-lang/scala-library/2.12.20/scala-library-2.12.20.jar",
        "org/scala-lang/modules/scala-xml_2.12/2.3.0/scala-xml_2.12-2.3.0.jar",
        "org/scala-lang/scala-reflect/2.12.20/scala-reflect-2.12.20.jar"
      )
    )
  )

  @Test def runtimeSelectionMigratesSbt1AndPreservesTheSbt2ExactCanary(): Unit = {
    Assert.assertTrue(SbtDependencyDetailedOutcomesSemanticContracts.Success
      .forRuntimeProfile(Sbt1Profile).isInstanceOf[Semantic])
    Assert.assertEquals(ExactTranscript, SbtDependencyDetailedOutcomesSemanticContracts.Success
      .forRuntimeProfile(Sbt2Profile))
  }

  @Test def profileContractsDeclareExactResourceMultisetsAndAcceptUnorderedResources(): Unit = {
    Profiles.foreach { profile =>
      val contract = contractFor(profile.runtimeProfile)
      Assert.assertEquals(2, contract.lifecycles.size)
      Assert.assertEquals(ProcessResultContract.DelegatedToHarness, contract.processResult)
      Assert.assertEquals(
        PlainOutputContract.Patterns(Vector(SbtTaskSummary, SbtTaskSummary)),
        contract.plainOutput
      )
      Assert.assertEquals(
        profile.firstRunPaths.map(MavenCentral + _).sorted,
        resourceUrls(contract, "first").sorted
      )
      Assert.assertEquals(
        profile.cachedRunPaths.map(MavenCentral + _).sorted,
        resourceUrls(contract, "second").sorted
      )
      Assert.assertEquals(2, resourceUrls(contract, "first").count(_.endsWith("scala-library-" +
        (if (profile.runtimeProfile == Sbt1Profile) "2.13.18" else "2.12.20") + ".pom")))

      verify(syntheticTranscript(contract), contract)
      verify(reverseResourcesWithinBlocks(syntheticTranscript(contract)), contract)
      verify(
        Vector(TaskSummary, TaskSummary) ++ blockLines(contract, "first") ++ blockLines(contract, "second"),
        contract
      )
    }
  }

  @Test def exactUrlsMultiplicitySummariesOwnershipAndDefaultDenyRejectMutations(): Unit = {
    Profiles.foreach { profile =>
      val contract = contractFor(profile.runtimeProfile)
      val valid = syntheticTranscript(contract)
      val firstPom = firstIndex(valid, line => line.contains("scala-library") && line.contains(".pom"))
      val duplicatePomIndexes = valid.indices.filter(index =>
        valid(index).contains("scala-library") && valid(index).contains(".pom")
      ).toVector
      require(duplicatePomIndexes.size == 2, s"Expected duplicated first-run library POM, got $duplicatePomIndexes")
      val wrongUrl = valid.updated(firstPom, valid(firstPom).replace("scala-library", "changed-library"))
      val missingDuplicate = valid.patch(duplicatePomIndexes.head, Vector.empty, 1)
      val wrongSummary = updateFirst(valid, _.contains("local cache hits, 0 downloads"),
        _.replace("local cache hits, 0 downloads", "local cache hits, 1 downloads"))
      val wrongFlow = updateFirst(valid, _.contains("/ global|] local cache hit"),
        _.replace(DependencyFlow, "foreign-flow"))
      val unexpectedMessage = valid :+ "##teamcity[fixtureUnexpected value='extra']"

      Vector(wrongUrl, missingDuplicate, wrongSummary, unexpectedMessage).foreach { mutated =>
        assertViolation(collect(mutated, contract), SemanticCardinalityFailure)
      }
      val wrongFlowFindings = collect(wrongFlow, contract)
      assertViolation(wrongFlowFindings, FlowOwnershipFailure)
      Assert.assertFalse(wrongFlowFindings.exists(finding =>
        finding.category == SemanticCardinalityFailure && finding.disposition == Violation))
      val missingFindings = collect(missingDuplicate, contract)
      Assert.assertTrue(missingFindings.exists(_.disposition == Blocked))
      Assert.assertFalse(missingFindings.exists(_.category == MatcherComplexityFailure))

      assertViolation(collect(valid :+ "unexpected plain output", contract), PlainOutputFailure)
      assertViolation(collect(valid :+ TaskSummary, contract), PlainOutputFailure)
    }
  }

  @Test def blockAndSummaryOrderingIsStrictWhileResourceOrderIsNot(): Unit = {
    Profiles.foreach { profile =>
      val contract = contractFor(profile.runtimeProfile)
      val valid = syntheticTranscript(contract)
      val firstSummary = firstIndex(valid, line =>
        line.contains("13 local cache hits") || line.contains("12 local cache hits"))
      val firstResource = firstIndex(valid, _.contains("/ global|] local cache hit"))
      val summaryBeforeResource = valid
        .updated(firstResource, valid(firstSummary))
        .updated(firstSummary, valid(firstResource))
      val reversedSummaryFindings = collect(summaryBeforeResource, contract)
      assertViolation(reversedSummaryFindings, OrderingFailure)
      Assert.assertFalse(reversedSummaryFindings.exists(_.category == SemanticCardinalityFailure))
      Assert.assertFalse(reversedSummaryFindings.exists(_.category == MatcherComplexityFailure))

      val firstClose = firstIndex(valid, _.startsWith("##teamcity[blockClosed"))
      val secondOpen = firstIndex(valid.drop(firstClose + 1), _.startsWith("##teamcity[blockOpened")) + firstClose + 1
      val overlappingBlocks = valid
        .updated(firstClose, valid(secondOpen))
        .updated(secondOpen, valid(firstClose))
      val overlappingFindings = collect(overlappingBlocks, contract)
      assertViolation(overlappingFindings, OrderingFailure, "event-assignment-ordering")
      assertBlocked(
        overlappingFindings,
        OrderingFailure,
        "edge:first-close->second-open"
      )
      Assert.assertFalse(overlappingFindings.exists(_.category == SemanticCardinalityFailure))
      Assert.assertFalse(overlappingFindings.exists(_.category == MatcherComplexityFailure))
    }
  }

  @Test def oneFinalAssertionAggregatesIndependentFailuresWithoutFalseCascades(): Unit = {
    val contract = contractFor(Sbt1Profile)
    val valid = syntheticTranscript(contract)
    val resource = firstIndex(valid, _.contains("jline-parent/3.29.0"))
    val summary = firstIndex(valid, _.contains("13 local cache hits"))
    val wrongOrder = valid.updated(resource, valid(summary)).updated(summary, valid(resource))
    val mutated = wrongOrder ++ Vector(
      "##teamcity[message status='NORMAL' text='unterminated'",
      "##teamcity[fixtureUnexpected value='extra']",
      "unexpected plain output"
    )
    val processCheckingContract = contract.copy(processResult = ProcessResultContract.Success)
    val failure = expectSemanticFailure(SbtSemanticOutputVerifier.verify(mutated, processCheckingContract, exitCode = 7))
    val findings = failure.failures

    Vector(
      WireProtocolFailure,
      OrderingFailure,
      SemanticCardinalityFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach { category =>
      assertViolation(findings, category)
      Assert.assertTrue(failure.getMessage.contains(s"[$category]"))
    }
    Vector(
      "wire:line-",
      "edge:first-resource-6->first-summary",
      "kind:fixtureUnexpected",
      "plain-output",
      "process-result"
    ).foreach(identity => Assert.assertTrue(
      s"Expected composite diagnostic to contain '$identity':\n${failure.getMessage}",
      failure.getMessage.contains(identity)
    ))
    Assert.assertFalse(findings.exists(finding =>
      finding.category == SemanticCardinalityFailure &&
        finding.disposition == Violation &&
        finding.semanticIdentity == "event-assignment"))
    Vector(
      "lifecycle:first-dependency-resolution",
      "lifecycle:second-dependency-resolution"
    ).foreach(identity => assertBlocked(findings, LifecycleFailure, identity))
    Assert.assertFalse(findings.exists(_.category == MatcherComplexityFailure))
    Assert.assertFalse(
      findings.filter(finding =>
        Set(LifecycleFailure, FlowOwnershipFailure).contains(finding.category) &&
          finding.disposition == Violation).mkString("\n"),
      findings.exists(finding =>
        Set(LifecycleFailure, FlowOwnershipFailure).contains(finding.category) &&
          finding.disposition == Violation)
    )
  }

  private def contractFor(runtimeProfile: String): SbtSemanticContract =
    SbtDependencyDetailedOutcomesSemanticContracts.semanticContractFor(runtimeProfile)

  private def syntheticTranscript(contract: SbtSemanticContract): Vector[String] =
    blockLines(contract, "first") ++ Vector(TaskSummary) ++ blockLines(contract, "second") ++ Vector(TaskSummary)

  private def blockLines(contract: SbtSemanticContract, label: String): Vector[String] = {
    val events = contract.events.filter(_.id.value.startsWith(s"$label-"))
    val open = events.find(_.id.value == s"$label-open").toVector.flatMap(renderEvent)
    val resources = events.filter(_.id.value.startsWith(s"$label-resource-")).reverse.flatMap(renderEvent)
    val summary = events.find(_.id.value == s"$label-summary").toVector.flatMap(renderEvent)
    val close = events.find(_.id.value == s"$label-close").toVector.flatMap(renderEvent)
    open ++ resources ++ summary ++ close
  }

  private def renderEvent(event: ExpectedSemanticEvent): Vector[String] = {
    def value(name: String): String = event.attributes.find(_._1 == name).map(_._2) match {
      case Some(Exact(expected)) => expected
      case Some(Embedded(prefix, _, suffix)) => s"${prefix}17${suffix}"
      case Some(DependencyResource(project, configuration, url, _)) =>
        s"[$project / $configuration] local cache hit $url"
      case other => throw new AssertionError(s"Cannot render $name=$other for ${event.id}.")
    }
    val line = event.kind match {
      case BlockOpened => s"##teamcity[blockOpened name='${value("name")}' flowId='$DependencyFlow']"
      case BlockClosed => s"##teamcity[blockClosed name='${value("name")}' flowId='$DependencyFlow']"
      case BuildLogMessage =>
        s"##teamcity[message status='${value("status")}' flowId='$DependencyFlow' text='${teamCityEscape(value("text"))}']"
      case other => throw new AssertionError(s"Cannot render ${other.wireName} for ${event.id}.")
    }
    Vector.fill(event.multiplicity)(line)
  }

  private def resourceUrls(contract: SbtSemanticContract, label: String): Vector[String] =
    contract.events.filter(_.id.value.startsWith(s"$label-resource-")).flatMap { event =>
      val url = event.attributes.collectFirst { case ("text", DependencyResource(_, _, value, _)) => value }
        .getOrElse(throw new AssertionError(s"Missing resource URL for ${event.id}."))
      Vector.fill(event.multiplicity)(url)
    }

  private def reverseResourcesWithinBlocks(lines: Vector[String]): Vector[String] = {
    def reverseBetween(current: Vector[String], openIndex: Int, summaryIndex: Int): Vector[String] = {
      val resourceIndexes = (openIndex + 1 until summaryIndex).filter(index =>
        current(index).startsWith("##teamcity[message") && current(index).contains("/ global|]")
      ).toVector
      val reversed = resourceIndexes.map(current).reverse
      resourceIndexes.zip(reversed).foldLeft(current) { case (result, (index, line)) => result.updated(index, line) }
    }
    val opens = lines.indices.filter(index => lines(index).startsWith("##teamcity[blockOpened")).toVector
    opens.foldLeft(lines) { (current, open) =>
      val summary = (open + 1 until current.size).find(index => current(index).contains("Dependency resolution finished"))
        .getOrElse(throw new AssertionError(s"Missing summary after block at $open."))
      reverseBetween(current, open, summary)
    }
  }

  private def collect(lines: Vector[String], contract: SbtSemanticContract): Vector[SbtSemanticFailure] =
    SbtSemanticOutputVerifier.collect(
      lines,
      contract,
      exitCode = 0,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def verify(lines: Vector[String], contract: SbtSemanticContract): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      contract,
      exitCode = 0,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def expectSemanticFailure(action: => Unit): SbtSemanticVerificationException = try {
    action
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def updateFirst(
    lines: Vector[String],
    predicate: String => Boolean,
    update: String => String
  ): Vector[String] = {
    val index = firstIndex(lines, predicate)
    lines.updated(index, update(lines(index)))
  }

  private def firstIndex(lines: Vector[String], predicate: String => Boolean): Int = {
    val index = lines.indexWhere(predicate)
    require(index >= 0, "Expected a matching transcript line.")
    index
  }

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory,
    semanticIdentity: String = ""
  ): Unit = Assert.assertTrue(
    s"Expected $category Violation in ${findings.map(finding =>
      (finding.category, finding.disposition, finding.semanticIdentity))}",
    findings.exists(finding =>
      finding.category == category &&
        finding.disposition == Violation &&
        (semanticIdentity.isEmpty || finding.semanticIdentity == semanticIdentity)
    )
  )

  private def assertBlocked(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory,
    semanticIdentity: String
  ): Unit = Assert.assertTrue(
    s"Expected $category Blocked for $semanticIdentity in ${findings.map(finding =>
      (finding.category, finding.disposition, finding.semanticIdentity))}",
    findings.exists(finding =>
      finding.category == category &&
        finding.disposition == Blocked &&
        finding.semanticIdentity == semanticIdentity
    )
  )

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
