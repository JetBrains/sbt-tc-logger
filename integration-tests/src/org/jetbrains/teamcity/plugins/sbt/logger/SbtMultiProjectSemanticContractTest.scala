package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

import scala.collection.mutable

class SbtMultiProjectSemanticContractTest {
  import SemanticValuePattern.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*
  import StructuredSbtDebugKind.*

  private val RuntimeProfiles = Vector(
    "sbt-1.4-jdk8",
    "sbt-1-jdk8",
    "sbt-1-jdk17",
    "sbt-2-jdk17"
  )

  @Test def everyRuntimeProfileSelectsSemanticOrHybridVerification(): Unit = {
    RuntimeProfiles.foreach { profile =>
      val normal = SbtMultiProjectSemanticContracts.Failure.forRuntimeProfile(profile)
      val debug = SbtMultiProjectSemanticContracts.FailureDebug.forRuntimeProfile(profile)
      Assert.assertNotEquals(s"Normal verification must not remain exact for $profile", ExactTranscript, normal)
      Assert.assertNotEquals(s"Debug verification must not remain exact for $profile", ExactTranscript, debug)
      if (profile == "sbt-2-jdk17") Assert.assertTrue(debug.isInstanceOf[Hybrid])
      else Assert.assertTrue(debug.isInstanceOf[Semantic])
    }
  }

  @Test def profileSpecificNonDebugAndDebugTranscriptsSatisfyTheirContracts(): Unit = {
    RuntimeProfiles.foreach { profile =>
      Vector(
        SbtMultiProjectSemanticContracts.Failure.forRuntimeProfile(profile),
        SbtMultiProjectSemanticContracts.FailureDebug.forRuntimeProfile(profile)
      ).foreach { mode => verify(syntheticTranscript(mode), mode) }
    }
  }

  @Test def detailedErrorCaretSpacingMatchesRuntimeOutput(): Unit = {
    RuntimeProfiles.foreach { profile =>
      val expectedCaretLine =
        if (profile == "sbt-2-jdk17") "[error]" + (" " * 38) + "^"
        else "[error]" + (" " * 35) + "^"
      val detailedErrors = effectiveContract(
        SbtMultiProjectSemanticContracts.Failure.forRuntimeProfile(profile)
      ).events.filter(_.id.value.endsWith("-detailed-error"))

      Assert.assertEquals(s"Detailed errors for $profile", 2, detailedErrors.size)
      detailedErrors.foreach { event =>
        val suffix = event.attributes.collectFirst {
          case ("text", Embedded(_, _, value)) => value
        }.getOrElse(throw new AssertionError(s"Missing embedded text suffix for ${event.id} in $profile"))

        Assert.assertEquals(s"Caret spacing for ${event.id} in $profile", expectedCaretLine, suffix.linesIterator.toVector.last)
      }
    }
  }

  @Test def contractsAcceptDifferentLegalCrossProjectInterleavings(): Unit = {
    RuntimeProfiles.foreach { profile =>
      Vector(
        SbtMultiProjectSemanticContracts.Failure.forRuntimeProfile(profile),
        SbtMultiProjectSemanticContracts.FailureDebug.forRuntimeProfile(profile)
      ).foreach { mode =>
        verify(syntheticTranscript(mode, reverseReadyOrder = false), mode)
        verify(syntheticTranscript(mode, reverseReadyOrder = true), mode)
      }
    }
  }

  @Test def wrongFlowSharedBaseAndSourceSuffixAreRejectedIndependently(): Unit = {
    val mode = SbtMultiProjectSemanticContracts.Failure.forRuntimeProfile("sbt-1-jdk17")
    val valid = syntheticTranscript(mode)
    val wrongFlow = updateFirst(valid, _.contains("compiler='Scala compiler |[project1|]'"),
      _.replace("101:compile:compiler", "102:compile:compiler"))
    val wrongPath = updateFirst(valid, _.contains("line='4'"),
      _.replace("/work/project1/HiProject1.scala", "/other/HiProject1.scala"))
    val wrongSuffix = valid.map(
      _.replace("/work/project1/HiProject1.scala", "/work/project1/Renamed.scala")
    )

    assertCategory(collect(wrongFlow, mode), FlowOwnershipFailure)
    assertCategory(collect(wrongPath, mode), SemanticCardinalityFailure)
    assertCategory(collect(wrongSuffix, mode), SemanticCardinalityFailure)
  }

  @Test def missingLifecycleBoundaryIsRejectedWithoutInventingAnOrderingViolation(): Unit = {
    val mode = SbtMultiProjectSemanticContracts.Failure.forRuntimeProfile("sbt-1-jdk17")
    val mutated = syntheticTranscript(mode).filterNot(
      _.contains("compilationFinished compiler='Scala compiler |[project1|]'")
    )
    val findings = collect(mutated, mode)

    assertCategory(findings, SemanticCardinalityFailure)
    assertCategory(findings, LifecycleFailure)
    Assert.assertFalse(findings.exists(finding =>
      finding.category == OrderingFailure && finding.disposition == Violation))
  }

  @Test def unknownStructuredDebugSentenceIsRejected(): Unit = {
    val mode = SbtMultiProjectSemanticContracts.FailureDebug.forRuntimeProfile("sbt-1-jdk17")
    val mutated = updateFirst(syntheticTranscript(mode), _.contains("not up to date."),
      _.replace("not up to date.", "unknown dependency state."))

    assertCategory(collect(mutated, mode), SemanticCardinalityFailure)
  }

  @Test def optionalCompilerBridgeMustBeAbsentOrComplete(): Unit = {
    val mode = SbtMultiProjectSemanticContracts.FailureDebug.forRuntimeProfile("sbt-1-jdk17")
    val withBridge = syntheticTranscript(mode, includeCompilerBridge = true)
    verify(withBridge, mode)
    val incomplete = withBridge.filterNot(_.contains("Compilation completed in"))
    val findings = collect(incomplete, mode)

    assertCategory(findings, SemanticCardinalityFailure)
    Assert.assertTrue(findings.exists(_.semanticIdentity.contains("compiler-bridge")))
  }

  @Test def optionalCompilerBridgeMustRemainInsideItsCompilationLane(): Unit = {
    val mode = SbtMultiProjectSemanticContracts.FailureDebug.forRuntimeProfile("sbt-1-jdk17")
    val valid = syntheticTranscript(mode, includeCompilerBridge = true)
    val announcementIndex = valid.indexWhere(line =>
      line.contains("Non-compiled module") && line.contains("flowId='101:compile:compiler'")
    )
    require(announcementIndex >= 0, "Synthetic project1 compiler-bridge announcement was not found.")
    val bridgePair = valid.slice(announcementIndex, announcementIndex + 2)
    val withoutBridge = valid.patch(announcementIndex, Vector.empty, bridgePair.size)
    val compilationStart = withoutBridge.indexWhere(
      _.contains("compilationStarted compiler='Scala compiler |[project1|]'")
    )
    val relocated = withoutBridge.patch(compilationStart, bridgePair, 0)
    val findings = collect(relocated, mode)

    Assert.assertTrue(findings.exists(finding =>
      finding.category == OrderingFailure &&
        finding.disposition == Violation &&
        finding.semanticIdentity == "edge:project1-compile-info->project1-bridge-announcement"
    ))
  }

  @Test def sbt2PlainPreludeAndSummaryMustSurroundServiceMessages(): Unit = {
    val mode = SbtMultiProjectSemanticContracts.FailureDebug.forRuntimeProfile("sbt-2-jdk17")
    val valid = syntheticTranscript(mode)
    val firstService = valid.indexWhere(_.startsWith("##teamcity["))
    val lastService = valid.lastIndexWhere(_.startsWith("##teamcity["))
    val prelude = valid.take(firstService)
    val services = valid.slice(firstService, lastService + 1)
    val summary = valid.drop(lastService + 1)
    require(prelude.size == 3 && summary.size == 1, "Unexpected synthetic SBT 2 plain-output shape.")

    val preludeAfterServices = services ++ prelude ++ summary
    val summaryBeforeServices = prelude ++ summary ++ services

    assertCategory(collect(preludeAfterServices, mode), PlainOutputFailure)
    assertCategory(collect(summaryBeforeServices, mode), PlainOutputFailure)

    val normalMode = SbtMultiProjectSemanticContracts.Failure.forRuntimeProfile("sbt-2-jdk17")
    val normal = syntheticTranscript(normalMode)
    val normalSummary = normal.last
    val summaryBeforeNormalServices = normalSummary +: normal.dropRight(1)
    assertCategory(collect(summaryBeforeNormalServices, normalMode), PlainOutputFailure)
  }

  @Test def finalSemanticAssertionReportsMultipleAttributeMismatchesTogether(): Unit = {
    val mode = SbtMultiProjectSemanticContracts.Failure.forRuntimeProfile("sbt-1-jdk17")
    val wrongProject1 = updateFirst(syntheticTranscript(mode), _.contains("inspection SEVERITY='ERROR' line='4'"),
      _.replace("message='invalid literal number'", "message='first changed diagnostic'"))
    val mutated = updateFirst(wrongProject1, _.contains("inspection SEVERITY='ERROR' line='6'"),
      _.replace("SEVERITY='ERROR'", "SEVERITY='WARNING'"))
    val failure = expectSemanticFailure(verify(mutated, mode))
    val identities = failure.findings.map(_.semanticIdentity).toSet

    Assert.assertTrue(identities.contains("event:project1-inspection"))
    Assert.assertTrue(identities.contains("event:project2-inspection"))
    Assert.assertTrue(failure.getMessage.contains("event:project1-inspection"))
    Assert.assertTrue(failure.getMessage.contains("event:project2-inspection"))
  }

  @Test def compoundMutationAggregatesIndependentFindingsWithoutCascadingOrderFailures(): Unit = {
    val mode = SbtMultiProjectSemanticContracts.Failure.forRuntimeProfile("sbt-1-jdk17")
    val missingBoundary = syntheticTranscript(mode).filterNot(
      _.contains("compilationFinished compiler='Scala compiler |[project1|]'")
    )
    val wrongOwnership = updateFirst(missingBoundary,
      _.contains("compiler='Scala compiler |[project2|]'"),
      _.replace("102:compile:compiler", "101:compile:compiler"))
    val mutated = wrongOwnership ++ Vector(
      "##teamcity[fixtureUnexpected value='extra']",
      "unexpected plain output"
    )
    val contract = effectiveContract(mode).copy(processResult = ProcessResultContract.Success)
    val findings = SbtSemanticOutputVerifier.collect(mutated, contract, exitCode = 7)

    Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach(assertCategory(findings, _))
    Assert.assertTrue(findings.exists(finding =>
      finding.category == LifecycleFailure && finding.disposition == Violation))
    Assert.assertFalse(findings.exists(finding =>
      finding.category == OrderingFailure && finding.disposition == Violation))
  }

  private def syntheticTranscript(
    mode: SbtOutputVerification,
    reverseReadyOrder: Boolean = false,
    includeCompilerBridge: Boolean = false
  ): Vector[String] = {
    val contract = effectiveContract(mode)
    val activeOptionalGroups = Option.when(includeCompilerBridge)(contract.optionalGroups).getOrElse(Vector.empty)
    val serviceMessages = topologicalEvents(contract, reverseReadyOrder, activeOptionalGroups).flatMap {
      case (event, explicitOwnership) =>
        Vector.fill(event.multiplicity)(renderEvent(event, contract, explicitOwnership))
    }
    val plainOutput = renderPlainOutput(contract.plainOutput)
    val before = plainOutput.collect { case RenderedPlainOutput(line, SyntheticPlainPlacement.BeforeServices) => line }
    val unconstrained = plainOutput.collect { case RenderedPlainOutput(line, SyntheticPlainPlacement.Unconstrained) => line }
    val after = plainOutput.collect { case RenderedPlainOutput(line, SyntheticPlainPlacement.AfterServices) => line }
    before ++ serviceMessages ++ unconstrained ++ after
  }

  private def topologicalEvents(
    contract: SbtSemanticContract,
    reverseReadyOrder: Boolean,
    activeOptionalGroups: Vector[OptionalSemanticEventGroup]
  ): Vector[(ExpectedSemanticEvent, Option[SemanticValuePattern])] = {
    final case class EventBlock(
      events: Vector[ExpectedSemanticEvent],
      ownership: Option[SemanticValuePattern]
    )

    val blocks = contract.events.map(event => EventBlock(Vector(event), None)) ++
      activeOptionalGroups.map(group => EventBlock(group.events, group.ownership))
    val eventToBlock = blocks.zipWithIndex.flatMap { case (block, blockIndex) =>
      block.events.map(_.id -> blockIndex)
    }.toMap
    val lifecycleEdges = contract.lifecycles.flatMap { lifecycle =>
      val members = lifecycle.members
      members.map(member => HappensBefore(lifecycle.start, member)) ++
        members.map(member => HappensBefore(member, lifecycle.finish)) :+
        HappensBefore(lifecycle.start, lifecycle.finish)
    }.toSet
    val eventEdges = contract.happensBefore ++ lifecycleEdges ++ activeOptionalGroups.flatMap(_.happensBefore)
    val edges = eventEdges.flatMap { edge =>
      for {
        before <- eventToBlock.get(edge.before)
        after <- eventToBlock.get(edge.after)
        if before != after
      } yield before -> after
    }
    val successors = edges.groupMap(_._1)(_._2).withDefaultValue(Set.empty)
    val inDegree = mutable.Map.from(blocks.indices.map(block => block -> edges.count(_._2 == block)))
    val result = Vector.newBuilder[(ExpectedSemanticEvent, Option[SemanticValuePattern])]
    val remaining = mutable.Set.from(blocks.indices)

    while (remaining.nonEmpty) {
      val ready = remaining.iterator.filter(block => inDegree(block) == 0).toVector.sorted
      require(ready.nonEmpty, "Synthetic transcript generator encountered a cyclic contract.")
      val selected = if (reverseReadyOrder) ready.last else ready.head
      blocks(selected).events.foreach(event => result += event -> blocks(selected).ownership)
      remaining -= selected
      successors(selected).foreach(block => inDegree.update(block, inDegree(block) - 1))
    }
    result.result()
  }

  private def renderEvent(
    event: ExpectedSemanticEvent,
    contract: SbtSemanticContract,
    explicitOwnership: Option[SemanticValuePattern] = None
  ): String = {
    val lifecycleOwnership = contract.lifecycles.collectFirst {
      case lifecycle if lifecycle.start == event.id || lifecycle.members.contains(event.id) ||
        lifecycle.finish == event.id => lifecycle.ownership
    }
    val attributes = event.attributes ++
      (explicitOwnership orElse lifecycleOwnership).filterNot(_ => event.attributes.exists(_._1 == "flowId"))
        .map("flowId" -> _)
    val renderedAttributes = attributes.map { case (name, pattern) =>
      s"$name='${teamCityEscape(representative(pattern))}'"
    }.mkString(" ")
    s"##teamcity[${event.kind.wireName} $renderedAttributes]"
  }

  private def representative(pattern: SemanticValuePattern): String = pattern match {
    case Exact(value) => value
    case Bound(key) => bindingValue(key)
    case Embedded(prefix, key, suffix) => prefix + bindingValue(key) + suffix
    case UnsignedDuration => "1.25"
    case CompilerBridgeAnnouncement =>
      "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.16. Compiling..."
    case CompilerBridgeCompletion => "[info]   Compilation completed in 1.25s."
    case StructuredSbtDebug(kind) => structuredDebugRepresentative(kind)
    case other => throw new AssertionError(s"No synthetic representative for $other")
  }

  private def bindingValue(key: SemanticBindingKey): String = key.kind match {
    case SemanticBindingKind.BuildId => key.name match {
      case "root-build" => "100"
      case "project1-build" => "101"
      case "project2-build" => "102"
      case other => throw new AssertionError(s"Unknown synthetic build binding $other")
    }
    case SemanticBindingKind.Path => key.name match {
      case "multi-project-source-base" => "/work"
      case "project1-output" => "/work/project1/target/classes"
      case "project2-output" => "/work/project2/target/classes"
      case other => throw new AssertionError(s"Unknown synthetic path binding $other")
    }
    case SemanticBindingKind.Flow => s"flow-${key.name}"
    case SemanticBindingKind.DurationMillis => "1250"
    case SemanticBindingKind.Value => s"value-${key.name}"
  }

  private def structuredDebugRepresentative(kind: StructuredSbtDebugKind): String = kind match {
    case DependencyCheck => "[debug] not up to date. inChanged = true, force = false"
    case DependencyUpdate => "[debug] Updating root..."
    case DependencyDone => "[debug] Done updating root"
    case IncrementalHeader => "[debug] [zinc] IncrementalCompile -----------"
    case IncrementalCompile => "[debug] IncrementalCompile.incrementalCompile"
    case PreviousStamps => "[debug] previous = Stamps for: 0 products, 0 sources, 0 libraries"
    case CurrentSources => "[debug] current source = Set()"
    case InitialChanges => "[debug] > initialChanges = InitialChanges(...)"
    case FullCompilation => "[debug] Full compilation, no sources in previous analysis."
    case InvalidatedSources => "[debug] all 1 sources are invalidated"
    case InitialIncludedNodes => "[debug] Initial set of included nodes: source"
    case RecompileAllSources =>
      "[debug] Recompiling all sources: number of invalidated sources > 50.0% of all sources"
    case CompilationCycle => "[debug] compilation cycle 1"
    case CompilerBridgeRetrieval =>
      "[debug] Getting org.scala-sbt:compiler-bridge_2.13:1.12.0:compile for Scala 2.13.16"
    case CachedCompiler =>
      "[debug] [zinc] Running cached compiler abc123 for Scala compiler version 2.13.18"
    case CompilerArguments =>
      "[debug] [zinc] The Scala compiler is invoked with:\n[debug] \t-classpath\n[debug] \t/tmp/classes"
    case CompilationFailed => "[debug] Compilation failed (CompilerInterface)"
    case CreatedClassFileManager =>
      "[debug] Created transactional ClassFileManager with tempDir = /tmp/classes.bak"
    case AboutToDeleteClassFiles => "[debug] About to delete class files:\n[debug] "
    case BackupClassFiles => "[debug] We backup class files:\n[debug] "
    case RollbackClassFiles => "[debug] Rolling back changes to class files."
    case RemoveGeneratedClasses => "[debug] Removing generated classes:\n[debug] "
    case RestoreClassFiles => "[debug] Restoring class files: \n[debug] "
    case RemoveTemporaryDirectory =>
      "[debug] Removing the temporary directory used for backing up class files: /tmp/classes.bak"
    case WroteProducts => "[debug] wrote /tmp/classes"
  }

  private enum SyntheticPlainPlacement {
    case BeforeServices, Unconstrained, AfterServices
  }

  private final case class RenderedPlainOutput(line: String, placement: SyntheticPlainPlacement)

  private def renderPlainOutput(contract: PlainOutputContract): Vector[RenderedPlainOutput] = contract match {
    case PlainOutputContract.RejectAll => Vector.empty
    case PlainOutputContract.Patterns(patterns) =>
      patterns.flatMap(renderPlainPattern(_, SyntheticPlainPlacement.Unconstrained))
    case other => throw new AssertionError(s"Synthetic transcript requires a concrete plain-output contract, got $other")
  }

  private def renderPlainPattern(
    pattern: PlainOutputPattern,
    placement: SyntheticPlainPlacement
  ): Vector[RenderedPlainOutput] = pattern match {
    case PlainOutputPattern.SbtTaskSummary =>
      Vector(RenderedPlainOutput("[error] elapsed time: 1.25 s, cache 50%, 3 tasks", placement))
    case PlainOutputPattern.SbtDebug(RawSbtDebugKind.CommandExecution) =>
      Vector(RenderedPlainOutput("[debug] > Exec(compile, None, None)", placement))
    case PlainOutputPattern.SbtDebug(RawSbtDebugKind.TaskEvaluation) =>
      Vector(RenderedPlainOutput("[debug] Evaluating tasks: Compile / compile", placement))
    case PlainOutputPattern.SbtDebug(RawSbtDebugKind.TaskRun) =>
      Vector(RenderedPlainOutput(
        "[debug] Running task... Cancel: Null, check cycles: false, forcegc: true",
        placement
      ))
    case PlainOutputPattern.BeforeServiceMessages(child) =>
      renderPlainPattern(child, SyntheticPlainPlacement.BeforeServices)
    case PlainOutputPattern.AfterServiceMessages(child) =>
      renderPlainPattern(child, SyntheticPlainPlacement.AfterServices)
    case other => throw new AssertionError(s"No synthetic plain-output representative for $other")
  }

  private def effectiveContract(mode: SbtOutputVerification): SbtSemanticContract = mode match {
    case Semantic(contract) => contract
    case Hybrid(contract, plainOutput) => contract.copy(plainOutput = plainOutput)
    case ExactTranscript => throw new AssertionError("Multi-project scenarios must not use exact verification.")
  }

  private def verify(lines: Vector[String], mode: SbtOutputVerification): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      effectiveContract(mode),
      exitCode = 1,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(lines: Vector[String], mode: SbtOutputVerification): Vector[SbtSemanticFailure] =
    SbtSemanticOutputVerifier.collect(
      lines,
      effectiveContract(mode),
      exitCode = 1,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def updateFirst(
    lines: Vector[String],
    predicate: String => Boolean,
    update: String => String
  ): Vector[String] = {
    val index = lines.indexWhere(predicate)
    require(index >= 0, "Synthetic transcript mutation target was not found.")
    lines.updated(index, update(lines(index)))
  }

  private def assertCategory(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category in ${findings.map(finding => finding.category -> finding.semanticIdentity)}",
    findings.exists(_.category == category)
  )

  private def expectSemanticFailure(action: => Unit): SbtSemanticVerificationException = try {
    action
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
