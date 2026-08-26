package org.jetbrains.teamcity.plugins.sbt.logger.utils

import scala.collection.mutable
import scala.util.matching.Regex

/** Default-deny semantic verifier for a bounded SBT logger transcript. */
private[logger] object SbtSemanticOutputVerifier {
  def verify(
    lines: Vector[String],
    contract: SbtSemanticContract,
    exitCode: Int,
    loggerVersion: Option[String] = None,
    sourceIndexOffset: Int = 0,
    delegated: SbtDelegatedVerification = SbtDelegatedVerification()
  ): Unit = verify(
    SbtOutputObservation.parseBounded(lines, exitCode, loggerVersion, sourceIndexOffset),
    contract,
    delegated
  )

  def verify(run: ObservedRun, contract: SbtSemanticContract): Unit =
    verify(run, contract, SbtDelegatedVerification())

  def verify(run: ObservedRun, contract: SbtSemanticContract, delegated: SbtDelegatedVerification): Unit = {
    val result = collect(run, contract, delegated)
    if (result.nonEmpty) throw new SbtSemanticVerificationException(result)
  }

  /** Non-throwing entry point for the future harness to combine semantic and delegated findings. */
  def collect(
    lines: Vector[String],
    contract: SbtSemanticContract,
    exitCode: Int,
    loggerVersion: Option[String] = None,
    sourceIndexOffset: Int = 0,
    delegated: SbtDelegatedVerification = SbtDelegatedVerification()
  ): Vector[SbtSemanticFailure] = collect(
    SbtOutputObservation.parseBounded(lines, exitCode, loggerVersion, sourceIndexOffset),
    contract,
    delegated
  )

  def collect(run: ObservedRun, contract: SbtSemanticContract): Vector[SbtSemanticFailure] =
    collect(run, contract, SbtDelegatedVerification())

  def collect(
    run: ObservedRun,
    contract: SbtSemanticContract,
    delegated: SbtDelegatedVerification
  ): Vector[SbtSemanticFailure] = {
    val failures = Vector.newBuilder[SbtSemanticFailure]

    SbtSemanticContractValidator.prepare(contract) match {
      case Left(syntaxFailures) =>
        failures ++= syntaxFailures
        failures ++= run.wireFailures
        failures ++= verifyPlainOutput(run.plainOutput, run.serviceMessages, contract.plainOutput, delegated)
        failures ++= verifyProcessResult(run.exitCode, contract.processResult, delegated)
      case Right(prepared) =>
        failures ++= run.wireFailures
        failures ++= verifyServiceMessages(run.serviceMessages, prepared, run.wireFailures)
        failures ++= verifyPlainOutput(run.plainOutput, run.serviceMessages, prepared.plainOutput, delegated)
        failures ++= verifyProcessResult(run.exitCode, prepared.processResult, delegated)
    }

    failures.result().distinct
  }

  private final case class MatchSolution(
    eventToObservation: Map[SemanticEventId, Int],
    bindings: Map[SemanticBindingKey, String],
    expectedEvents: Vector[ExpectedSemanticEvent],
    happensBefore: Set[HappensBefore],
    activeOptionalGroups: Set[String],
    uncertainEdges: Set[HappensBefore] = Set.empty
  )

  private final case class SearchResult(solutions: Vector[MatchSolution], budgetExceeded: Boolean)

  private final case class PartialOptionalAssessment(
    possibleGroups: Set[String],
    definiteGroups: Set[String],
    findings: Vector[SbtSemanticFailure]
  )

  private enum OwnershipMode {
    case Enforce
    case Ignore
  }

  private def verifyServiceMessages(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    wireFailures: Vector[SbtSemanticFailure]
  ): Vector[SbtSemanticFailure] = {
    val failures = Vector.newBuilder[SbtSemanticFailure]
    if (wireFailures.nonEmpty) {
      failures += SbtSemanticFailure(
        SbtVerificationFailureCategory.SemanticCardinalityFailure,
        "Exact total event consumption cannot be proven while malformed TeamCity-looking lines remain.",
        wireFailures.map(_.semanticIdentity),
        SbtFindingDisposition.Blocked,
        "event-assignment"
      )
    }

    var fullMapping = Option.empty[MatchSolution]
    var unresolvedAssignment = false
    var assignmentBlockedReason = Option.empty[String]
    var diagnosticSolutions = Vector.empty[MatchSolution]
    var diagnosticOwnershipMode = OwnershipMode.Enforce
    {
      val exact = search(observed, contract, OwnershipMode.Enforce)
      diagnosticSolutions = Option.unless(exact.budgetExceeded)(exact.solutions).getOrElse(Vector.empty)
      if (exact.budgetExceeded) {
        assignmentBlockedReason = Some("exact semantic matching exceeded its state budget")
        failures += SbtSemanticFailure(
          SbtVerificationFailureCategory.MatcherComplexityFailure,
          s"Semantic matching exceeded its explicit ${contract.matcherStateBudget}-state budget.",
          Vector("Refine event attributes, bindings, or explicit occurrence ordinals."),
          semanticIdentity = "event-assignment"
        )
      } else if (exact.solutions.size > 1) {
        assignmentBlockedReason = Some("exact semantic event assignment is ambiguous")
        failures += ambiguityFailure(exact.solutions, observed)
      } else if (exact.solutions.size == 1) {
        fullMapping = exact.solutions.headOption
      } else {
        val ownershipRelaxed = search(observed, contract, OwnershipMode.Ignore)
        diagnosticOwnershipMode = OwnershipMode.Ignore
        diagnosticSolutions = Option.unless(ownershipRelaxed.budgetExceeded)(ownershipRelaxed.solutions)
          .getOrElse(Vector.empty)
        if (ownershipRelaxed.budgetExceeded) {
          assignmentBlockedReason = Some("ownership-relaxed semantic matching exceeded its state budget")
          failures += SbtSemanticFailure(
            SbtVerificationFailureCategory.MatcherComplexityFailure,
            s"Ownership diagnosis exceeded its explicit ${contract.matcherStateBudget}-state budget.",
            Vector("Refine stable attributes before relying on named ownership bindings."),
            semanticIdentity = "event-assignment"
          )
        } else if (ownershipRelaxed.solutions.size == 1) {
          fullMapping = ownershipRelaxed.solutions.headOption
        } else if (ownershipRelaxed.solutions.size > 1) {
          assignmentBlockedReason = Some("ownership-relaxed semantic event assignment is ambiguous")
          failures += ambiguityFailure(ownershipRelaxed.solutions, observed)
        } else {
          unresolvedAssignment = !hasStructuralFailures(observed, contract)
        }
      }
    }

    val optionalAssessment = fullMapping.fold(
      assessPartialOptionalGroups(
        observed,
        contract,
        diagnosticSolutions,
        assignmentBlockedReason,
        wireFailures,
        diagnosticOwnershipMode
      )
    )(_ => PartialOptionalAssessment(Set.empty, Set.empty, Vector.empty))
    val diagnosticGroups = contract.optionalGroups.filter(group => optionalAssessment.possibleGroups.contains(group.name))
    val diagnosticEvents = fullMapping.map(_.expectedEvents).getOrElse(
      contract.events ++ diagnosticGroups.flatMap(_.events)
    )
    if (fullMapping.isEmpty) {
      failures ++= cardinalityFailures(observed, contract, wireFailures)
      failures ++= attributeFailures(
        observed,
        contract,
        diagnosticEvents,
        optionalAssessment.definiteGroups,
        wireFailures
      )
      failures ++= optionalAssessment.findings
    }
    val diagnosticEdges = contract.happensBefore ++ diagnosticGroups.flatMap(_.happensBefore)
    val uncertainEdges = diagnosticGroups.filterNot(group => optionalAssessment.definiteGroups.contains(group.name))
      .flatMap(_.happensBefore).toSet
    val effectiveMapping = fullMapping.getOrElse(MatchSolution(
      safePartialMapping(observed, contract, diagnosticEvents),
      Map.empty,
      diagnosticEvents,
      diagnosticEdges,
      optionalAssessment.possibleGroups,
      uncertainEdges
    ))
    val dependentBlockedReason = assignmentBlockedReason.orElse(
      Option.when(unresolvedAssignment)("no complete semantic event assignment exists")
    )
    val bindingEvents = fullMapping.map(_.expectedEvents).getOrElse(
      contract.events ++ contract.optionalGroups.filter(group => optionalAssessment.definiteGroups.contains(group.name))
        .flatMap(_.events)
    )
    val bindingFailures = bindingConflictFailures(
      effectiveMapping.eventToObservation,
      observed,
      contract,
      bindingEvents
    )
    failures ++= lifecycleBalanceFailures(
      effectiveMapping.eventToObservation,
      contract,
      wireFailures,
      dependentBlockedReason
    )
    failures ++= bindingFailures
    failures ++= blockedBindingFindings(
      effectiveMapping.eventToObservation,
      contract,
      effectiveMapping.expectedEvents,
      wireFailures,
      dependentBlockedReason
    )
    if (unresolvedAssignment && bindingFailures.isEmpty) {
      failures += SbtSemanticFailure(
        SbtVerificationFailureCategory.SemanticCardinalityFailure,
        "Expected and observed messages cannot be assigned one-to-one under the declared attribute contract.",
        diagnosticOverview(observed, diagnosticEvents),
        semanticIdentity = "event-assignment"
      )
    }
    failures ++= orderingFailures(effectiveMapping, observed, contract, wireFailures, dependentBlockedReason)
    failures.result().distinct
  }

  private def cardinalityFailures(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    wireFailures: Vector[SbtSemanticFailure]
  ): Vector[SbtSemanticFailure] = {
    val expectedByKind = contract.events.groupMapReduce(_.kind)(event => Vector(event))(_ ++ _)
    val observedByKind = observed.groupMapReduce(_.kind)(message => Vector(message))(_ ++ _)
    val optionalKinds = contract.optionalGroups.flatMap(_.events.map(_.kind)).toSet
    val kinds = (expectedByKind.keySet ++ optionalKinds ++ observedByKind.keySet).toVector.sortBy(_.wireName)
    val deltas = kinds.flatMap { kind =>
      val expected = expectedByKind.getOrElse(kind, Vector.empty)
      val actual = observedByKind.getOrElse(kind, Vector.empty)
      val allowedCounts = possibleExpectedCounts(kind, contract)
      Option.when(!allowedCounts.contains(actual.size))((kind, expected, actual, allowedCounts))
    }

    deltas.map { case (kind, expected, actual, allowedCounts) =>
      val minimumExpected = allowedCounts.minOption.getOrElse(0)
      val maximumExpected = allowedCounts.maxOption.getOrElse(0)
      val isPotentiallyMissing = actual.size < minimumExpected
      val malformedMayCompleteCount = wireFailures.nonEmpty && actual.size < maximumExpected
      val disposition =
        if (malformedMayCompleteCount) SbtFindingDisposition.Blocked
        else SbtFindingDisposition.Violation
      val summary =
        if (isPotentiallyMissing) s"Missing ${minimumExpected - actual.size} parsed ${kind.wireName} event(s)."
        else if (actual.size > maximumExpected) s"Observed ${actual.size - maximumExpected} extra parsed ${kind.wireName} event(s)."
        else s"Observed ${actual.size} parsed ${kind.wireName} event(s), which cannot satisfy optional all-or-none groups."
      val context =
        if (isPotentiallyMissing) Vector(s"Expected semantic events: ${expected.map(_.id).mkString(", ")}") ++
          Option.when(wireFailures.nonEmpty)("Malformed lines may contain the unavailable event(s).").toVector
        else Vector(s"Allowed exact counts: ${allowedCounts.toVector.sorted.mkString(", ")}") ++ actual.map(describe) ++
          Option.when(malformedMayCompleteCount)("Malformed lines may complete an allowed event count.").toVector
      SbtSemanticFailure(
        SbtVerificationFailureCategory.SemanticCardinalityFailure,
        summary,
        context,
        disposition,
        s"kind:${kind.wireName}"
      )
    }
  }

  private def possibleExpectedCounts(
    kind: ObservedServiceMessageKind,
    contract: PreparedSemanticContract
  ): Set[Int] = {
    val required = contract.events.count(_.kind == kind)
    contract.optionalGroups.foldLeft(Set(required)) { (counts, group) =>
      val contribution = group.events.count(_.kind == kind)
      counts ++ counts.map(_ + contribution)
    }
  }

  private def lifecycleBalanceFailures(
    mapping: Map[SemanticEventId, Int],
    contract: PreparedSemanticContract,
    wireFailures: Vector[SbtSemanticFailure],
    assignmentBlockedReason: Option[String]
  ): Vector[SbtSemanticFailure] = {
    contract.lifecycles.flatMap { lifecycle =>
      val missing = Vector(lifecycle.start, lifecycle.finish).filterNot(mapping.contains)
      Option.when(missing.nonEmpty)(SbtSemanticFailure(
        SbtVerificationFailureCategory.LifecycleFailure,
        s"Lifecycle '${lifecycle.name}' cannot prove balanced start and finish.",
        Vector(s"Unavailable boundaries: ${missing.mkString(", ")}") ++
          assignmentBlockedReason.map(reason => s"Assignment prerequisite: $reason.").toVector ++
          Option.when(wireFailures.nonEmpty)("Malformed lines may contain an unavailable boundary.").toVector,
        if (wireFailures.nonEmpty || assignmentBlockedReason.nonEmpty) SbtFindingDisposition.Blocked
        else SbtFindingDisposition.Violation,
        s"lifecycle:${lifecycle.name}"
      ))
    }
  }

  private def attributeFailures(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    events: Vector[ExpectedSemanticEvent],
    definiteOptionalGroups: Set[String],
    wireFailures: Vector[SbtSemanticFailure]
  ): Vector[SbtSemanticFailure] = {
    val occurrenceByIndex = occurrences(observed)
    val optionalOwner = contract.optionalGroups.flatMap(group => group.events.map(_.id -> group.name)).toMap
    events.flatMap { event =>
      val sameKind = observed.indices.filter { index =>
        observed(index).kind == event.kind && event.occurrence.forall(_ == occurrenceByIndex(index))
      }
      if (sameKind.isEmpty) Vector.empty
      else {
        val anyCompatible = sameKind.exists { index =>
          matchEvent(event, observed(index), Map.empty, contract.distinctBindings, OwnershipMode.Ignore).isDefined
        }
        if (anyCompatible) Vector.empty
        else {
          val raw = sameKind.flatMap { index =>
            describeAttributeMismatch(event, observed(index))
          }
          Vector(SbtSemanticFailure(
            SbtVerificationFailureCategory.SemanticCardinalityFailure,
            s"No ${event.kind.wireName} observation satisfies attributes for semantic event '${event.id}'.",
            Vector(s"Expected attributes: ${describeAttributes(event.attributes)}") ++ raw ++
              Option.when(wireFailures.nonEmpty)(
                "Malformed lines may contain an unavailable matching event."
              ).toVector,
            disposition = if (wireFailures.nonEmpty) SbtFindingDisposition.Blocked else optionalOwner.get(event.id) match {
              case Some(group) if !definiteOptionalGroups.contains(group) => SbtFindingDisposition.Blocked
              case _ => SbtFindingDisposition.Violation
            },
            semanticIdentity = s"event:${event.id}"
          ))
        }
      }
    }
  }

  private def assessPartialOptionalGroups(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    candidateSolutions: Vector[MatchSolution],
    assignmentBlockedReason: Option[String],
    wireFailures: Vector[SbtSemanticFailure],
    ownershipMode: OwnershipMode = OwnershipMode.Enforce
  ): PartialOptionalAssessment = {
    val structuralMatches = contract.optionalGroups.map { group =>
      group.name -> maximumCompatibleAssignments(group.events, observed, contract)
    }.toMap
    val needsSoundActivationSearch = candidateSolutions.nonEmpty || assignmentBlockedReason.exists { reason =>
      reason.contains("ambiguous") || reason.contains("budget")
    }
    val constrainedActivation = Option.when(needsSoundActivationSearch)(
      analyzeOptionalActivation(observed, contract, ownershipMode)
    )
    val possible = constrainedActivation.map(_._1).getOrElse(
      structuralMatches.collect { case (name, count) if count > 0 => name }.toSet
    )
    val definiteEvidence = contract.optionalGroups.filter(group => possible.contains(group.name)).flatMap { group =>
      val exclusiveObservationIndexes = observed.indices.filter { index =>
        val matchesRequired = contract.events.exists(event => compatibleIgnoringOwnership(event, observed(index), contract))
        val matchingGroups = contract.optionalGroups.filter(_.events.exists(event =>
          compatibleIgnoringOwnership(event, observed(index), contract)
        )).map(_.name).toSet
        !matchesRequired && matchingGroups == Set(group.name)
      }.toSet
      Option.when(maximumCompatibleAssignments(group.events, observed, contract, Some(exclusiveObservationIndexes)) > 0)(group.name)
    }.toSet
    val definite = constrainedActivation.map(_._2).getOrElse(definiteEvidence)
    val findings = contract.optionalGroups.filter(group => possible.contains(group.name)).flatMap { group =>
      val matched = structuralMatches(group.name)
      val partial = Option.when(matched > 0 && matched < group.events.size)(SbtSemanticFailure(
        SbtVerificationFailureCategory.SemanticCardinalityFailure,
        s"Optional semantic group '${group.name}' is only partially present.",
        Vector(s"Matched $matched of ${group.events.size} required group events.") ++
          assignmentBlockedReason.map(reason => s"Assignment prerequisite: $reason.").toVector ++
          Option.when(wireFailures.nonEmpty)("Malformed lines may contain a missing group event.").toVector,
        if (definite.contains(group.name) && wireFailures.isEmpty) SbtFindingDisposition.Violation
        else SbtFindingDisposition.Blocked,
        semanticIdentity = s"optional-group:${group.name}"
      )).toVector
      val nonAdjacent = Option.when(
        matched == group.events.size && group.requiresAdjacency && !hasAdjacentStructuralPair(group, observed, contract)
      )(SbtSemanticFailure(
        SbtVerificationFailureCategory.OrderingFailure,
        s"Optional compiler-bridge group '${group.name}' is present but not adjacent.",
        assignmentBlockedReason.map(reason => s"Assignment prerequisite: $reason.").toVector,
        if (definite.contains(group.name)) SbtFindingDisposition.Violation else SbtFindingDisposition.Blocked,
        semanticIdentity = s"optional-group:${group.name}:adjacency"
      )).toVector
      partial ++ nonAdjacent
    }
    PartialOptionalAssessment(possible, definite, findings)
  }

  private def hasAdjacentStructuralPair(
    group: PreparedOptionalSemanticEventGroup,
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract
  ): Boolean = group.events match {
    case Vector(first, second) => observed.indices.exists { firstIndex =>
      observed.indices.exists { secondIndex =>
        observed(secondIndex).sourceIndex == observed(firstIndex).sourceIndex + 1 &&
          compatibleIgnoringOwnership(first, observed(firstIndex), contract) &&
          compatibleIgnoringOwnership(second, observed(secondIndex), contract)
      }
    }
    case _ => false
  }

  private def maximumCompatibleAssignments(
    events: Vector[ExpectedSemanticEvent],
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    allowedObservationIndexes: Option[Set[Int]] = None
  ): Int = {
    val occurrenceByIndex = occurrences(observed)
    val allowed = allowedObservationIndexes.getOrElse(observed.indices.toSet)
    val candidates = events.map { event =>
      allowed.toVector.sorted.filter { index =>
        event.occurrence.forall(_ == occurrenceByIndex(index)) &&
          matchEvent(event, observed(index), Map.empty, contract.distinctBindings, OwnershipMode.Ignore).nonEmpty
      }.toVector
    }
    val observationToEvent = mutable.HashMap.empty[Int, Int]
    def augment(eventIndex: Int, visited: mutable.Set[Int]): Boolean = candidates(eventIndex).exists { observationIndex =>
      if (visited.contains(observationIndex)) false
      else {
        visited += observationIndex
        observationToEvent.get(observationIndex) match {
          case None =>
            observationToEvent.update(observationIndex, eventIndex)
            true
          case Some(previousEvent) if augment(previousEvent, visited) =>
            observationToEvent.update(observationIndex, eventIndex)
            true
          case _ => false
        }
      }
    }
    events.indices.count(eventIndex => augment(eventIndex, mutable.HashSet.empty))
  }

  private def compatibleIgnoringOwnership(
    event: ExpectedSemanticEvent,
    observed: ObservedServiceMessage,
    contract: PreparedSemanticContract
  ): Boolean = matchEvent(event, observed, Map.empty, contract.distinctBindings, OwnershipMode.Ignore).nonEmpty

  private def hasStructuralFailures(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract
  ): Boolean =
    cardinalityFailures(observed, contract, Vector.empty).nonEmpty ||
      attributeFailures(observed, contract, contract.events, Set.empty, Vector.empty)
        .exists(_.disposition == SbtFindingDisposition.Violation)

  private def analyzeOptionalActivation(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    ownershipMode: OwnershipMode
  ): (Set[String], Set[String]) = {
    val analyses = contract.optionalGroups.map { group =>
      val active = search(
        observed,
        contract,
        ownershipMode,
        optionalActivation = Map(group.name -> true),
        solutionLimit = 1
      )
      val inactive = search(
        observed,
        contract,
        ownershipMode,
        optionalActivation = Map(group.name -> false),
        solutionLimit = 1
      )
      val activePossible = active.solutions.nonEmpty || active.budgetExceeded
      val inactiveImpossible = inactive.solutions.isEmpty && !inactive.budgetExceeded
      val definitelyActive = active.solutions.nonEmpty && !active.budgetExceeded && inactiveImpossible
      (group.name, activePossible, definitelyActive)
    }
    val possible = analyses.collect { case (name, true, _) => name }.toSet
    val definite = analyses.collect { case (name, _, true) => name }.toSet
    possible -> definite
  }

  private def search(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    ownershipMode: OwnershipMode,
    optionalActivation: Map[String, Boolean] = Map.empty,
    solutionLimit: Int = 2
  ): SearchResult = {
    val occurrenceByIndex = occurrences(observed)
    val solutions = mutable.ArrayBuffer.empty[MatchSolution]
    val structurallyPossibleGroups = contract.optionalGroups.filter { group =>
      maximumCompatibleAssignments(group.events, observed, contract) == group.events.size
    }.map(_.name).toSet
    var visitedStates = 0
    var budgetExceeded = false

    def loop(
      remaining: Vector[ExpectedSemanticEvent],
      available: Set[Int],
      bindings: Map[SemanticBindingKey, String],
      mapping: Map[SemanticEventId, Int],
      expectedEvents: Vector[ExpectedSemanticEvent],
      happensBefore: Set[HappensBefore],
      activeOptionalGroups: Set[String]
    ): Unit = {
      if (solutions.size >= solutionLimit || budgetExceeded) return
      visitedStates += 1
      if (visitedStates > contract.matcherStateBudget) {
        budgetExceeded = true
        return
      }
      if (remaining.isEmpty) {
        if (available.isEmpty && bridgeAdjacencySatisfied(activeOptionalGroups, mapping, observed, contract)) {
          solutions += MatchSolution(mapping, bindings, expectedEvents, happensBefore, activeOptionalGroups)
        }
        return
      }

      val ready = remaining.filter(event => contract.canonicalPrevious.get(event.id).forall(mapping.contains))
      val alternatives = ready.map { event =>
        val candidates = available.toVector.sorted.flatMap { index =>
          val occurrenceMatches = event.occurrence.forall(_ == occurrenceByIndex(index))
          val canonicalOrderMatches = contract.canonicalPrevious.get(event.id)
            .flatMap(mapping.get)
            .forall(previous => observed(previous).sourceIndex < observed(index).sourceIndex)
          Option.when(occurrenceMatches && canonicalOrderMatches)(
            matchEvent(event, observed(index), bindings, contract.distinctBindings, ownershipMode).map(index -> _)
          ).flatten
        }
        event -> candidates
      }
      if (alternatives.isEmpty) return
      val (event, candidates) = alternatives.minBy(_._2.size)
      if (candidates.isEmpty) return
      val nextRemaining = remaining.filterNot(_.id == event.id)
      candidates.foreach { case (index, nextBindings) =>
        loop(
          nextRemaining,
          available - index,
          nextBindings,
          mapping.updated(event.id, index),
          expectedEvents,
          happensBefore,
          activeOptionalGroups
        )
      }
    }

    def chooseOptionalGroups(
      groupIndex: Int,
      expectedEvents: Vector[ExpectedSemanticEvent],
      happensBefore: Set[HappensBefore],
      activeOptionalGroups: Set[String]
    ): Unit = {
      if (solutions.size >= solutionLimit || budgetExceeded) return
      visitedStates += 1
      if (visitedStates > contract.matcherStateBudget) {
        budgetExceeded = true
        return
      }
      if (!optionalSelectionFeasible(
        groupIndex,
        expectedEvents,
        observed,
        contract,
        structurallyPossibleGroups,
        optionalActivation
      )) return
      if (groupIndex == contract.optionalGroups.size) {
        if (expectedEvents.size == observed.size) {
          loop(
            expectedEvents,
            observed.indices.toSet,
            Map.empty,
            Map.empty,
            expectedEvents,
            happensBefore,
            activeOptionalGroups
          )
        }
      } else {
        val group = contract.optionalGroups(groupIndex)
        if (!optionalActivation.get(group.name).contains(true)) {
          chooseOptionalGroups(groupIndex + 1, expectedEvents, happensBefore, activeOptionalGroups)
        }
        if (!optionalActivation.get(group.name).contains(false) && structurallyPossibleGroups.contains(group.name)) {
          chooseOptionalGroups(
            groupIndex + 1,
            expectedEvents ++ group.events,
            happensBefore ++ group.happensBefore,
            activeOptionalGroups + group.name
          )
        }
      }
    }

    if (contract.optionalGroups.isEmpty) {
      if (contract.events.size == observed.size) {
        loop(
          contract.events,
          observed.indices.toSet,
          Map.empty,
          Map.empty,
          contract.events,
          contract.happensBefore,
          Set.empty
        )
      }
    } else chooseOptionalGroups(0, contract.events, contract.happensBefore, Set.empty)
    SearchResult(solutions.toVector, budgetExceeded)
  }

  private def optionalSelectionFeasible(
    groupIndex: Int,
    expectedEvents: Vector[ExpectedSemanticEvent],
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    structurallyPossibleGroups: Set[String],
    optionalActivation: Map[String, Boolean]
  ): Boolean = {
    if (expectedEvents.size > observed.size) return false
    val remainingGroups = contract.optionalGroups.drop(groupIndex).filter { group =>
      structurallyPossibleGroups.contains(group.name) && !optionalActivation.get(group.name).contains(false)
    }
    if (expectedEvents.size + remainingGroups.map(_.events.size).sum < observed.size) return false
    val expectedByKind = expectedEvents.groupMapReduce(_.kind)(_ => 1)(_ + _)
    val observedByKind = observed.groupMapReduce(_.kind)(_ => 1)(_ + _)
    val remainingByKind = remainingGroups.flatMap(_.events).groupMapReduce(_.kind)(_ => 1)(_ + _)
    (expectedByKind.keySet ++ observedByKind.keySet ++ remainingByKind.keySet).forall { kind =>
      val current = expectedByKind.getOrElse(kind, 0)
      val actual = observedByKind.getOrElse(kind, 0)
      current <= actual && actual <= current + remainingByKind.getOrElse(kind, 0)
    }
  }

  private def bridgeAdjacencySatisfied(
    activeGroups: Set[String],
    mapping: Map[SemanticEventId, Int],
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract
  ): Boolean = contract.optionalGroups.filter(group =>
    activeGroups.contains(group.name) && group.requiresAdjacency
  ).forall { group =>
    val sourceIndexes = group.events.flatMap(event => mapping.get(event.id).map(index => observed(index).sourceIndex)).sorted
    sourceIndexes.size == 2 && sourceIndexes(1) == sourceIndexes(0) + 1
  }

  /**
   * Maps only events with one stable candidate and discards collisions where two expectations want the same source
   * event. This supports independent diagnostics after an unrelated lane has a missing or extra event.
   */
  private def safePartialMapping(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    events: Vector[ExpectedSemanticEvent]
  ): Map[SemanticEventId, Int] = {
    val occurrenceByIndex = occurrences(observed)
    val activeIds = events.map(_.id).toSet
    def canonicalRoot(id: SemanticEventId): SemanticEventId =
      contract.canonicalPrevious.get(id).filter(activeIds.contains).fold(id)(canonicalRoot)
    def canonicalDepth(id: SemanticEventId): Int =
      contract.canonicalPrevious.get(id).filter(activeIds.contains).fold(0)(previous => canonicalDepth(previous) + 1)
    val canonicalIds = activeIds.filter(id =>
      contract.canonicalPrevious.contains(id) || contract.canonicalPrevious.values.toSet.contains(id)
    )
    val nonCanonicalEvents = events.filterNot(event => canonicalIds.contains(event.id))
    val canonicalMappings = canonicalIds.groupBy(canonicalRoot).toVector.flatMap { case (_, ids) =>
      val orderedIds = ids.toVector.sortBy(canonicalDepth)
      val prototype = events.find(_.id == orderedIds.head).get
      val candidates = observed.indices.filter { index =>
        matchEvent(prototype, observed(index), Map.empty, contract.distinctBindings, OwnershipMode.Ignore).isDefined
      }.toVector.sortBy(index => observed(index).sourceIndex)
      val overlapsNonClone = candidates.exists { index =>
        nonCanonicalEvents.exists { event =>
          event.occurrence.forall(_ == occurrenceByIndex(index)) &&
            matchEvent(event, observed(index), Map.empty, contract.distinctBindings, OwnershipMode.Ignore).isDefined
        }
      }
      if (candidates.size <= orderedIds.size && !overlapsNonClone) orderedIds.zip(candidates) else Vector.empty
    }
    val uniqueCandidates = events.filterNot(event => canonicalIds.contains(event.id)).flatMap { event =>
      val candidates = observed.indices.filter { index =>
        event.occurrence.forall(_ == occurrenceByIndex(index)) &&
          matchEvent(event, observed(index), Map.empty, contract.distinctBindings, OwnershipMode.Ignore).isDefined
      }
      Option.when(candidates.size == 1)(event.id -> candidates.head)
    } ++ canonicalMappings
    val collisions = uniqueCandidates.groupMap(_._2)(_._1).collect { case (index, ids) if ids.size > 1 => index }.toSet
    uniqueCandidates.filterNot { case (_, index) => collisions.contains(index) }.toMap
  }

  private final case class CapturedBindingValue(
    event: SemanticEventId,
    attribute: String,
    value: String,
    message: ObservedServiceMessage
  )

  private def bindingConflictFailures(
    mapping: Map[SemanticEventId, Int],
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    events: Vector[ExpectedSemanticEvent]
  ): Vector[SbtSemanticFailure] = {
    val values = mutable.HashMap.empty[SemanticBindingKey, Vector[CapturedBindingValue]].withDefaultValue(Vector.empty)
    events.foreach { event =>
      mapping.get(event.id).foreach { index =>
        val message = observed(index)
        event.attributes.foreach { case (attribute, pattern) =>
          capturedBinding(pattern, message.attributes(attribute)).foreach { case (key, value) =>
            values.update(key, values(key) :+ CapturedBindingValue(event.id, attribute, value, message))
          }
        }
      }
    }

    val inconsistent = values.toVector.flatMap { case (key, captures) =>
      Option.when(captures.map(_.value).distinct.size > 1) {
        val expected = captures.head
        val reused = captures.tail.filter(_.value == expected.value)
        val conflicting = captures.tail.filterNot(_.value == expected.value)
        val context = Vector(
          s"Expected/reused value '${expected.value}' was established by event '${expected.event}' " +
            s"attribute '${expected.attribute}' at source line ${expected.message.sourceLineNumber}.",
          s"Raw: ${expected.message.rawLine}"
        ) ++ reused.flatMap { capture =>
          Vector(
            s"Reused value '${capture.value}' in event '${capture.event}' attribute '${capture.attribute}' " +
              s"at source line ${capture.message.sourceLineNumber}.",
            s"Raw: ${capture.message.rawLine}"
          )
        } ++ conflicting.flatMap { capture =>
          Vector(
            s"Conflicting value '${capture.value}' in event '${capture.event}' attribute '${capture.attribute}' " +
              s"at source line ${capture.message.sourceLineNumber}.",
            s"Raw: ${capture.message.rawLine}"
          )
        }
        SbtSemanticFailure(
          if (key.kind.isOwnership) SbtVerificationFailureCategory.FlowOwnershipFailure
          else SbtVerificationFailureCategory.SemanticCardinalityFailure,
          s"Named ${key.kind.displayName} binding '$key' has conflicting values.",
          context,
          semanticIdentity = s"binding:${key.kind.displayName}:${key.name}"
        )
      }
    }
    val nonDistinct = contract.distinctBindings.toVector.flatMap { pair =>
      val first = values(pair.first).map(_.value).distinct
      val second = values(pair.second).map(_.value).distinct
      Option.when(first.size == 1 && second.size == 1 && first.head == second.head) {
        val captures = values(pair.first) ++ values(pair.second)
        SbtSemanticFailure(
          if (pair.first.kind.isOwnership || pair.second.kind.isOwnership) SbtVerificationFailureCategory.FlowOwnershipFailure
          else SbtVerificationFailureCategory.SemanticCardinalityFailure,
          s"Bindings ${pair.first} and ${pair.second} must be distinct, but both are '${first.head}'.",
          captures.flatMap { capture =>
            Vector(
              s"Event '${capture.event}' attribute '${capture.attribute}' at source line ${capture.message.sourceLineNumber}.",
              s"Raw: ${capture.message.rawLine}"
            )
          },
          semanticIdentity = s"distinct-bindings:${pair.first.name}:${pair.second.name}"
        )
      }
    }
    inconsistent ++ nonDistinct
  }

  private def blockedBindingFindings(
    mapping: Map[SemanticEventId, Int],
    contract: PreparedSemanticContract,
    events: Vector[ExpectedSemanticEvent],
    wireFailures: Vector[SbtSemanticFailure],
    assignmentBlockedReason: Option[String]
  ): Vector[SbtSemanticFailure] = {
    val uses = events.flatMap { event =>
      event.attributes.flatMap { case (_, pattern) =>
        bindingKey(pattern).map(event.id -> _)
      }
    }.groupMap(_._2)(_._1)
    val distinctKeys = contract.distinctBindings.flatMap(pair => Set(pair.first, pair.second))
    uses.toVector.flatMap { case (key, eventIds) =>
      val unavailable = eventIds.distinct.filterNot(mapping.contains)
      val identitySensitive = eventIds.distinct.size > 1 || distinctKeys.contains(key)
      Option.when(identitySensitive && unavailable.nonEmpty)(SbtSemanticFailure(
        if (key.kind.isOwnership) SbtVerificationFailureCategory.FlowOwnershipFailure
        else SbtVerificationFailureCategory.SemanticCardinalityFailure,
        s"Named ${key.kind.displayName} binding '$key' cannot be checked across every declared event.",
        Option.when(unavailable.nonEmpty)(s"Unavailable semantic events: ${unavailable.mkString(", ")}").toVector ++
          assignmentBlockedReason.map(reason => s"Assignment prerequisite: $reason.").toVector ++
          Option.when(wireFailures.nonEmpty)("Malformed lines may contain a required binding occurrence.").toVector,
        SbtFindingDisposition.Blocked,
        s"binding:${key.kind.displayName}:${key.name}"
      ))
    }
  }

  private def capturedBinding(
    pattern: SemanticValuePattern,
    value: String
  ): Option[(SemanticBindingKey, String)] = pattern match {
    case SemanticValuePattern.Bound(key) if key.kind.accepts(value) => Some(key -> value)
    case SemanticValuePattern.Embedded(prefix, key, suffix)
      if value.startsWith(prefix) && value.endsWith(suffix) && value.length >= prefix.length + suffix.length =>
      val captured = value.substring(prefix.length, value.length - suffix.length)
      Option.when(key.kind.accepts(captured))(key -> captured)
    case _ => None
  }

  private def bindingKey(pattern: SemanticValuePattern): Option[SemanticBindingKey] = pattern match {
    case SemanticValuePattern.Bound(key) => Some(key)
    case SemanticValuePattern.Embedded(_, key, _) => Some(key)
    case _ => None
  }

  private def matchEvent(
    expected: ExpectedSemanticEvent,
    observed: ObservedServiceMessage,
    initialBindings: Map[SemanticBindingKey, String],
    distinctBindings: Set[DistinctSemanticBindings],
    ownershipMode: OwnershipMode
  ): Option[Map[SemanticBindingKey, String]] = {
    if (expected.kind != observed.kind) return None
    val expectedNames = expected.attributes.map(_._1).toSet
    if (expectedNames != observed.attributes.keySet) return None

    expected.attributes.sortBy(_._1).foldLeft(Option(initialBindings)) {
      case (Some(bindings), (name, pattern)) =>
        observed.attributes.get(name).flatMap(value => matchValue(pattern, value, bindings, distinctBindings, ownershipMode))
      case (None, _) => None
    }
  }

  private def matchValue(
    pattern: SemanticValuePattern,
    value: String,
    bindings: Map[SemanticBindingKey, String],
    distinctBindings: Set[DistinctSemanticBindings],
    ownershipMode: OwnershipMode
  ): Option[Map[SemanticBindingKey, String]] = pattern match {
    case SemanticValuePattern.Exact(expected) => Option.when(value == expected)(bindings)
    case SemanticValuePattern.Bound(key) => bind(key, value, bindings, distinctBindings, ownershipMode)
    case SemanticValuePattern.Embedded(prefix, key, suffix) =>
      Option.when(value.startsWith(prefix) && value.endsWith(suffix) && value.length >= prefix.length + suffix.length) {
        value.substring(prefix.length, value.length - suffix.length)
      }.flatMap(captured => bind(key, captured, bindings, distinctBindings, ownershipMode))
    case SemanticValuePattern.UnsignedDuration => Option.when(isUnsignedDuration(value))(bindings)
    case resource: SemanticValuePattern.DependencyResource =>
      Option.when(matchesDependencyResource(resource, value))(bindings)
    case SemanticValuePattern.CompilerBridgeAnnouncement =>
      Option.when(isCompilerBridgeAnnouncement(value))(bindings)
    case SemanticValuePattern.CompilerBridgeCompletion =>
      Option.when(isCompilerBridgeCompletion(value))(bindings)
    case SemanticValuePattern.StructuredSbtDebug(kind) =>
      Option.when(matchesStructuredSbtDebug(kind, value))(bindings)
    case failure: SemanticValuePattern.UserFailure => Option.when(matchesUserFailure(failure, value))(bindings)
  }

  private val UnsignedDurationPattern = "[0-9]+(?:\\.[0-9]+)?".r
  private val DownloadSizePattern = "(?:size unknown|[0-9]+ B|[0-9]+\\.[0-9] (?:KiB|MiB))"
  private val SbtTaskSummaryPattern =
    "^\\[(?:success|error)\\] (?:elapsed time: [0-9]+(?:\\.[0-9]+)? s, cache [0-9]+%, .+|Total time: [0-9]+(?:\\.[0-9]+)? s(?:, completed .+)?)$".r
  private val CompilerBridgeAnnouncementPattern =
    "^\\[info\\] Non-compiled module 'compiler-bridge_[A-Za-z0-9_.-]+' for Scala [0-9]+(?:\\.[0-9]+)+\\. Compiling\\.\\.\\.$".r
  private val CompilerBridgeCompletionPattern =
    "^\\[info\\]   Compilation completed in [0-9]+(?:\\.[0-9]+)?s\\.$".r

  private def isUnsignedDuration(value: String): Boolean = UnsignedDurationPattern.matches(value)

  private def matchesDependencyResource(
    pattern: SemanticValuePattern.DependencyResource,
    value: String
  ): Boolean = {
    val prefix = s"[${pattern.project} / ${pattern.configuration}] "
    if (!value.startsWith(prefix)) return false
    val detail = value.stripPrefix(prefix)
    pattern.outcomes.exists {
      case DependencyResourceOutcome.LocalCacheHit => detail == s"local cache hit ${pattern.url}"
      case DependencyResourceOutcome.Downloaded =>
        val metadataPrefix = s"downloaded ${pattern.url} ("
        detail.startsWith(metadataPrefix) && detail.endsWith(")") && {
          val metadata = detail.substring(metadataPrefix.length, detail.length - 1)
          metadata.matches(s"$DownloadSizePattern, $UnsignedDurationPattern (?:ms|s)")
        }
      case DependencyResourceOutcome.FailedDownloadAttempt =>
        val metadataPrefix = s"failed download attempt ${pattern.url} (after "
        detail.startsWith(metadataPrefix) && detail.endsWith(")") && {
          val duration = detail.substring(metadataPrefix.length, detail.length - 1)
          duration.matches(s"$UnsignedDurationPattern (?:ms|s)")
        }
    }
  }

  private def isCompilerBridgeAnnouncement(value: String): Boolean =
    CompilerBridgeAnnouncementPattern.matches(value)

  private def isCompilerBridgeCompletion(value: String): Boolean =
    CompilerBridgeCompletionPattern.matches(value)

  private def matchesStructuredSbtDebug(kind: StructuredSbtDebugKind, value: String): Boolean = {
    val patterns: Vector[Regex] = kind match {
      case StructuredSbtDebugKind.DependencyCheck =>
        Vector("""^\[debug\] not up to date\. inChanged = (?:true|false), force = (?:true|false)$""".r)
      case StructuredSbtDebugKind.DependencyUpdate =>
        Vector("""^\[debug\] Updating (?:\.\.\.|[A-Za-z0-9_.-]+\.\.\.)$""".r)
      case StructuredSbtDebugKind.DependencyDone =>
        Vector("""^\[debug\] Done updating [A-Za-z0-9_.-]*$""".r)
      case StructuredSbtDebugKind.IncrementalHeader =>
        Vector("""^\[debug\] \[zinc\] IncrementalCompile -+$""".r)
      case StructuredSbtDebugKind.IncrementalCompile =>
        Vector("""^\[debug\] IncrementalCompile\.incrementalCompile$""".r)
      case StructuredSbtDebugKind.PreviousStamps =>
        Vector("""^\[debug\] previous = Stamps for: [0-9]+ products, [0-9]+ sources, [0-9]+ libraries$""".r)
      case StructuredSbtDebugKind.CurrentSources =>
        Vector("""^\[debug\] current source = Set\([^\r\n]*\)$""".r)
      case StructuredSbtDebugKind.InitialChanges =>
        Vector("""^\[debug\] > initialChanges = InitialChanges\([^\r\n]+\)$""".r)
      case StructuredSbtDebugKind.FullCompilation =>
        Vector("""^\[debug\] Full compilation, no sources in previous analysis\.$""".r)
      case StructuredSbtDebugKind.InvalidatedSources =>
        Vector("""^\[debug\] all [0-9]+ sources are invalidated$""".r)
      case StructuredSbtDebugKind.InitialIncludedNodes =>
        Vector("""^\[debug\] Initial set of included nodes: ?[^\r\n]*$""".r)
      case StructuredSbtDebugKind.RecompileAllSources => Vector(
        """^\[debug\] Recompiling all sources: number of invalidated sources > [0-9]+(?:\.[0-9]+)?(?:%| percent) of all sources$""".r
      )
      case StructuredSbtDebugKind.CompilationCycle =>
        Vector("""^\[debug\] compilation cycle [0-9]+$""".r)
      case StructuredSbtDebugKind.CompilerBridgeRetrieval => Vector(
        """^\[debug\] Getting org\.scala-sbt:compiler-bridge_[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:compile for Scala [0-9]+(?:\.[0-9]+)+$""".r,
        """^\[debug\] Returning already retrieved and compiled bridge: \S+\.$""".r
      )
      case StructuredSbtDebugKind.CachedCompiler => Vector(
        """^\[debug\] \[zinc\] Running cached compiler \S+ for Scala [Cc]ompiler version [0-9]+(?:\.[0-9]+)+$""".r
      )
      case StructuredSbtDebugKind.CompilerArguments => Vector(
        """^\[debug\] \[zinc\] The Scala compiler is invoked with:(?:\n\[debug\] \t?[^\r\n]+)+$""".r
      )
      case StructuredSbtDebugKind.CompilationFailed =>
        Vector("""^\[debug\] Compilation failed(?: \(CompilerInterface\))?$""".r)
      case StructuredSbtDebugKind.CreatedClassFileManager => Vector(
        """^\[debug\] Created transactional ClassFileManager with tempDir = \S+/classes\.bak$""".r
      )
      case StructuredSbtDebugKind.AboutToDeleteClassFiles =>
        Vector("""^\[debug\] About to delete class files:(?:\n\[debug\] [^\r\n]*)+$""".r)
      case StructuredSbtDebugKind.BackupClassFiles =>
        Vector("""^\[debug\] We backup class files:(?:\n\[debug\] [^\r\n]*)+$""".r)
      case StructuredSbtDebugKind.RollbackClassFiles =>
        Vector("""^\[debug\] Rolling back changes to class files\.$""".r)
      case StructuredSbtDebugKind.RemoveGeneratedClasses =>
        Vector("""^\[debug\] Removing generated classes:(?:\n\[debug\] [^\r\n]*)+$""".r)
      case StructuredSbtDebugKind.RestoreClassFiles =>
        Vector("""^\[debug\] Restoring class files: ?(?:\n\[debug\] [^\r\n]*)+$""".r)
      case StructuredSbtDebugKind.RemoveTemporaryDirectory => Vector(
        """^\[debug\] Removing the temporary directory used for backing up class files: \S+/classes\.bak$""".r
      )
      case StructuredSbtDebugKind.WroteProducts =>
        Vector("""^\[debug\] wrote \S+/classes$""".r)
    }
    patterns.exists(_.matches(value))
  }

  private def matchesUserFailure(pattern: SemanticValuePattern.UserFailure, value: String): Boolean = {
    // One final newline is common in framework-rendered details; further/interior blank lines remain contractual.
    val normalized = if (value.endsWith("\n")) value.dropRight(1) else value
    if (normalized.endsWith("\n") || !normalized.startsWith(pattern.prefix + "\n")) return false
    val frames = normalized.substring(pattern.prefix.length + 1).split("\n", -1).toVector
    if (frames.exists(_.isEmpty) || frames.size < pattern.userFrames.size) return false

    val userFrameStarts = (0 to (frames.size - pattern.userFrames.size)).filter { start =>
      frames.slice(start, start + pattern.userFrames.size) == pattern.userFrames
    }
    if (userFrameStarts.size != 1) return false
    // Each exact user frame must occur only in that one ordered sequence, even if it resembles a framework frame.
    if (pattern.userFrames.exists(frame => frames.count(_ == frame) != 1)) return false

    val userStart = userFrameStarts.head
    val frameworkFrames = frames.take(userStart) ++ frames.drop(userStart + pattern.userFrames.size)
    frameworkFrames.size <= pattern.maximumFrameworkFrames &&
      frameworkFramesAreRecognized(pattern, frameworkFrames)
  }

  private def frameworkFramesAreRecognized(
    pattern: SemanticValuePattern.UserFailure,
    frames: Vector[String]
  ): Boolean =
    frames.forall { frame =>
      isRecognizedFrameworkFrame(pattern.framework, frame) ||
        (pattern.framework == RecognizedTestFramework.ScalaTest && frameLocation(frame)
          .flatMap(scalaTestGeneratedExecutionOwner(_, allowPlainRun = true))
          .exists(pattern.allowedGeneratedOwners.contains))
    }

  private def isRecognizedFrameworkFrame(framework: RecognizedTestFramework, frame: String): Boolean = {
    val trimmed = frame.stripPrefix("\t").trim
    if (trimmed.matches("\\.\\.\\. [0-9]+ more")) return true
    // A "Caused by:" line carries user-visible failure information; callers must include it in the exact prefix.
    if (!trimmed.startsWith("at ")) return false
    val location = trimmed.stripPrefix("at ")
    val frameworkPrefixes = framework match {
      case RecognizedTestFramework.ScalaTest => Set("org.scalatest.")
      case RecognizedTestFramework.Specs2 => Set("org.specs2.")
      case RecognizedTestFramework.JUnit => Set("org.junit.", "junit.", "com.novocode.junit.")
    }
    val commonPrefixes = Set(
      "sbt.", "xsbti.", "scala.", "java.", "java.base/", "jdk.internal.", "sun.reflect."
    )
    (frameworkPrefixes ++ commonPrefixes).exists(location.startsWith)
  }

  private def frameLocation(frame: String): Option[String] = {
    val trimmed = frame.stripPrefix("\t").trim
    Option.when(trimmed.startsWith("at "))(trimmed.stripPrefix("at "))
  }

  private def scalaTestGeneratedExecutionOwner(
    location: String,
    allowPlainRun: Boolean
  ): Option[String] = {
    val open = location.lastIndexOf('(')
    if (open <= 0 || !location.endsWith(")")) return None
    val ownerAndMethod = location.substring(0, open)
    val separator = ownerAndMethod.lastIndexOf('.')
    if (separator <= 0) return None
    val owner = ownerAndMethod.substring(0, separator)
    val method = ownerAndMethod.substring(separator + 1)
    val className = owner.substring(owner.lastIndexOf('.') + 1)
    val source = location.substring(open + 1, location.length - 1)
    val scalaTestMixinMethod =
      method.startsWith("org$scalatest$") && Set("$run", "$runTest", "$runTests").exists(method.endsWith)
    val generatedMethod = scalaTestMixinMethod || (allowPlainRun && Set("run", "runTest", "runTests").contains(method))

    Option.when(
      className.endsWith("Test") &&
      generatedMethod &&
      source.matches(s"${java.util.regex.Pattern.quote(className)}\\.scala:[0-9]+")
    )(owner)
  }

  private def bind(
    key: SemanticBindingKey,
    value: String,
    bindings: Map[SemanticBindingKey, String],
    distinctBindings: Set[DistinctSemanticBindings],
    ownershipMode: OwnershipMode
  ): Option[Map[SemanticBindingKey, String]] = {
    if (!key.kind.accepts(value)) return None
    bindings.get(key) match {
      case Some(existing) if existing == value => Some(bindings)
      case Some(_) if ownershipMode == OwnershipMode.Ignore && key.kind.isOwnership => Some(bindings)
      case Some(_) => None
      case None =>
        val updated = bindings.updated(key, value)
        val distinct = distinctBindings.forall { pair =>
          if (ownershipMode == OwnershipMode.Ignore && (pair.first.kind.isOwnership || pair.second.kind.isOwnership)) true
          else (updated.get(pair.first), updated.get(pair.second)) match {
              case (Some(first), Some(second)) => first != second
              case _ => true
            }
        }
        Option.when(distinct)(updated)
    }
  }

  private def orderingFailures(
    solution: MatchSolution,
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    wireFailures: Vector[SbtSemanticFailure],
    assignmentBlockedReason: Option[String]
  ): Vector[SbtSemanticFailure] = solution.happensBefore.toVector
    .sortBy(edge => (edge.before.value, edge.after.value))
    .flatMap { edge =>
      val beforeIndex = solution.eventToObservation.get(edge.before)
      val afterIndex = solution.eventToObservation.get(edge.after)
      (beforeIndex, afterIndex) match {
        case (Some(beforeValue), Some(afterValue)) if observed(beforeValue).sourceIndex >= observed(afterValue).sourceIndex =>
          val before = observed(beforeValue)
          val after = observed(afterValue)
          Some(SbtSemanticFailure(
            SbtVerificationFailureCategory.OrderingFailure,
            if (solution.uncertainEdges.contains(edge))
              s"Possible optional happens-before edge '${edge.before}' -> '${edge.after}' cannot be proven."
            else s"Required happens-before edge '${edge.before}' -> '${edge.after}' is reversed.",
            Vector(describe(before), describe(after)) ++
              Option.when(solution.uncertainEdges.contains(edge))(
                "Optional-group activation remains ambiguous."
              ).toVector,
            if (solution.uncertainEdges.contains(edge)) SbtFindingDisposition.Blocked
            else SbtFindingDisposition.Violation,
            semanticIdentity = s"edge:${edge.before}->${edge.after}"
          ))
        case (Some(_), Some(_)) => None
        case _ =>
          val missing = Vector(
            Option.when(beforeIndex.isEmpty)(edge.before),
            Option.when(afterIndex.isEmpty)(edge.after)
          ).flatten
          Some(SbtSemanticFailure(
            SbtVerificationFailureCategory.OrderingFailure,
            s"Required happens-before edge '${edge.before}' -> '${edge.after}' cannot be checked.",
            Vector(s"Unavailable semantic events: ${missing.mkString(", ")}") ++
              assignmentBlockedReason.map(reason => s"Assignment prerequisite: $reason.").toVector ++
              Option.when(wireFailures.nonEmpty)("Malformed lines may contain an unavailable endpoint.").toVector,
            SbtFindingDisposition.Blocked,
            s"edge:${edge.before}->${edge.after}"
          ))
      }
    }

  private def ambiguityFailure(
    solutions: Vector[MatchSolution],
    observed: Vector[ObservedServiceMessage]
  ): SbtSemanticFailure = {
    val differing = (solutions(0).eventToObservation.keySet ++ solutions(1).eventToObservation.keySet).toVector
      .sortBy(_.value)
      .flatMap { id =>
        val first = solutions(0).eventToObservation.get(id)
        val second = solutions(1).eventToObservation.get(id)
        Option.when(first != second)(
          s"$id may map to source lines ${first.map(index => observed(index).sourceLineNumber).getOrElse("?")} or " +
            second.map(index => observed(index).sourceLineNumber).getOrElse("?")
        )
      }
    SbtSemanticFailure(
      SbtVerificationFailureCategory.MatcherComplexityFailure,
      "Semantic event assignment is ambiguous; the contract must be refined instead of choosing arbitrarily.",
      differing,
      semanticIdentity = "event-assignment"
    )
  }

  private def verifyPlainOutput(
    observed: Vector[ObservedPlainLine],
    serviceMessages: Vector[ObservedServiceMessage],
    contract: PlainOutputContract,
    delegated: SbtDelegatedVerification
  ): Vector[SbtSemanticFailure] = contract match {
    case PlainOutputContract.RejectAll if observed.nonEmpty =>
      Vector(SbtSemanticFailure(
        SbtVerificationFailureCategory.PlainOutputFailure,
        s"Observed ${observed.size} undeclared plain-output line${if (observed.size == 1) "" else "s"}.",
        observed.map(describePlain),
        semanticIdentity = "plain-output"
      ))
    case PlainOutputContract.RejectAll => Vector.empty
    case PlainOutputContract.Exact(expected) =>
      val actual = observed.map(_.rawLine)
      if (actual == expected) Vector.empty
      else Vector(SbtSemanticFailure(
        SbtVerificationFailureCategory.PlainOutputFailure,
        s"Plain output differs (expected ${expected.size} lines, observed ${actual.size}).",
        Vector(s"Expected: ${expected.mkString(" | ")}") ++ observed.map(describePlain),
        semanticIdentity = "plain-output"
      ))
    case PlainOutputContract.Declared(description, accepts) =>
      val rejected = observed.filterNot(line => accepts(line.rawLine))
      if (rejected.isEmpty) Vector.empty
      else Vector(SbtSemanticFailure(
        SbtVerificationFailureCategory.PlainOutputFailure,
        s"Plain output violates declared classifier '$description'.",
        rejected.map(describePlain),
        semanticIdentity = "plain-output"
      ))
    case PlainOutputContract.Patterns(patterns) =>
      if (matchesPlainPatterns(patterns, observed, serviceMessages)) Vector.empty
      else Vector(SbtSemanticFailure(
        SbtVerificationFailureCategory.PlainOutputFailure,
        s"Plain output does not satisfy the declared finite ordered patterns or transcript placement " +
          s"(${patterns.size} top-level patterns, ${observed.size} observed lines).",
        Vector(s"Expected patterns: ${patterns.mkString(" | ")}") ++ observed.map(describePlain),
        semanticIdentity = "plain-output"
      ))
    case PlainOutputContract.DelegatedToHybrid if delegated.plainOutputVerified => Vector.empty
    case PlainOutputContract.DelegatedToHybrid => Vector(SbtSemanticFailure(
      SbtVerificationFailureCategory.PlainOutputFailure,
      "Plain-output verification is delegated to Hybrid mode, but the caller supplied no verification evidence.",
      disposition = SbtFindingDisposition.Blocked,
      semanticIdentity = "plain-output"
    ))
  }

  private def matchesPlainPatterns(
    patterns: Vector[PlainOutputPattern],
    lines: Vector[ObservedPlainLine],
    serviceMessages: Vector[ObservedServiceMessage]
  ): Boolean = {
    val memo = mutable.HashMap.empty[(Int, Int), Boolean]
    def loop(patternIndex: Int, lineIndex: Int): Boolean = memo.getOrElseUpdate((patternIndex, lineIndex), {
      if (patternIndex == patterns.size) lineIndex == lines.size
      else patterns(patternIndex) match {
        case PlainOutputPattern.OptionalGroup(_, children) =>
          loop(patternIndex + 1, lineIndex) ||
            matchesRequiredPlainPatterns(children, lines, lineIndex, serviceMessages).exists { nextLineIndex =>
              loop(patternIndex + 1, nextLineIndex)
            }
        case pattern if lineIndex < lines.size && matchesPlainLine(pattern, lines(lineIndex), serviceMessages) =>
          loop(patternIndex + 1, lineIndex + 1)
        case _ => false
      }
    })
    loop(0, 0)
  }

  private def matchesRequiredPlainPatterns(
    patterns: Vector[PlainOutputPattern],
    lines: Vector[ObservedPlainLine],
    start: Int,
    serviceMessages: Vector[ObservedServiceMessage]
  ): Option[Int] = patterns.foldLeft(Option(start)) {
    case (Some(index), pattern) if index < lines.size && !pattern.isInstanceOf[PlainOutputPattern.OptionalGroup] &&
      matchesPlainLine(pattern, lines(index), serviceMessages) => Some(index + 1)
    case _ => None
  }

  private def matchesPlainLine(
    pattern: PlainOutputPattern,
    line: ObservedPlainLine,
    serviceMessages: Vector[ObservedServiceMessage]
  ): Boolean = pattern match {
    case PlainOutputPattern.Exact(expected) => line.rawLine == expected
    case PlainOutputPattern.SbtTaskSummary => SbtTaskSummaryPattern.matches(line.rawLine)
    case PlainOutputPattern.SbtDebug(kind) => matchesRawSbtDebug(kind, line.rawLine)
    case PlainOutputPattern.BeforeServiceMessages(child) =>
      serviceMessages.headOption.forall(line.sourceIndex < _.sourceIndex) &&
        matchesPlainLine(child, line, serviceMessages)
    case PlainOutputPattern.AfterServiceMessages(child) =>
      serviceMessages.lastOption.forall(line.sourceIndex > _.sourceIndex) &&
        matchesPlainLine(child, line, serviceMessages)
    case PlainOutputPattern.CompilerBridgeAnnouncement => isCompilerBridgeAnnouncement(line.rawLine)
    case PlainOutputPattern.CompilerBridgeCompletion => isCompilerBridgeCompletion(line.rawLine)
    case _: PlainOutputPattern.OptionalGroup => false
  }

  private def matchesRawSbtDebug(kind: RawSbtDebugKind, line: String): Boolean = kind match {
    case RawSbtDebugKind.CommandExecution => line.matches("""^\[debug\] > Exec\([^\r\n]+\)$""")
    case RawSbtDebugKind.TaskEvaluation => line.matches("""^\[debug\] Evaluating tasks: [^\r\n]+$""")
    case RawSbtDebugKind.TaskRun => line.matches(
      """^\[debug\] Running task\.\.\. Cancel: [^,\r\n]+, check cycles: (?:true|false), forcegc: (?:true|false)$"""
    )
  }

  private def verifyProcessResult(
    observedExitCode: Int,
    contract: ProcessResultContract,
    delegated: SbtDelegatedVerification
  ): Vector[SbtSemanticFailure] = contract match {
    case ProcessResultContract.DelegatedToHarness if delegated.processResultVerified => Vector.empty
    case ProcessResultContract.DelegatedToHarness => Vector(SbtSemanticFailure(
      SbtVerificationFailureCategory.ProcessResultFailure,
      "Process-result verification is delegated to the outer harness, but the caller supplied no verification evidence.",
      disposition = SbtFindingDisposition.Blocked,
      semanticIdentity = "process-result"
    ))
    case ProcessResultContract.Success if observedExitCode == 0 => Vector.empty
    case ProcessResultContract.Success => Vector(SbtSemanticFailure(
      SbtVerificationFailureCategory.ProcessResultFailure,
      s"Expected the process to succeed with exit code 0, observed $observedExitCode.",
      semanticIdentity = "process-result"
    ))
    case ProcessResultContract.Failure if observedExitCode != 0 => Vector.empty
    case ProcessResultContract.Failure => Vector(SbtSemanticFailure(
      SbtVerificationFailureCategory.ProcessResultFailure,
      "Expected the process to fail with a nonzero exit code, observed 0.",
      semanticIdentity = "process-result"
    ))
    case ProcessResultContract.ExitCode(expected) if expected == observedExitCode => Vector.empty
    case ProcessResultContract.ExitCode(expected) => Vector(SbtSemanticFailure(
      SbtVerificationFailureCategory.ProcessResultFailure,
      s"Expected process exit code $expected, observed $observedExitCode.",
      semanticIdentity = "process-result"
    ))
  }

  private def occurrences(observed: Vector[ObservedServiceMessage]): Map[Int, Int] = {
    val counts = mutable.HashMap.empty[ObservedServiceMessageKind, Int].withDefaultValue(0)
    observed.indices.map { index =>
      val kind = observed(index).kind
      val ordinal = counts(kind) + 1
      counts.update(kind, ordinal)
      index -> ordinal
    }.toMap
  }

  private def diagnosticOverview(
    observed: Vector[ObservedServiceMessage],
    expected: Vector[ExpectedSemanticEvent]
  ): Vector[String] =
    Vector(s"Expected events: ${expected.map(_.id).mkString(", ")}") ++ observed.map(describe)

  private def describe(message: ObservedServiceMessage): String = {
    val flow = message.flowId.fold("")(value => s", flow '$value'")
    s"Source line ${message.sourceLineNumber}: ${message.kind.wireName}$flow: ${message.rawLine}"
  }

  private def describePlain(line: ObservedPlainLine): String =
    s"Source line ${line.sourceLineNumber}: ${line.rawLine}"

  private def describeAttributeMismatch(
    expected: ExpectedSemanticEvent,
    observed: ObservedServiceMessage
  ): Vector[String] = {
    val expectedByName = expected.attributes.toMap
    val missing = expectedByName.keySet.diff(observed.attributes.keySet).toVector.sorted
    val unexpected = observed.attributes.keySet.diff(expectedByName.keySet).toVector.sorted
    val mismatched = expected.attributes.flatMap { case (name, pattern) =>
      observed.attributes.get(name).flatMap { value =>
        Option.when(matchValue(pattern, value, Map.empty, Set.empty, OwnershipMode.Ignore).isEmpty)(
          s"attribute '$name' has value '$value', expected $pattern"
        )
      }
    }
    Vector(s"Candidate source line ${observed.sourceLineNumber}:") ++
      Option.when(missing.nonEmpty)(s"missing keys: ${missing.mkString(", ")}").toVector ++
      Option.when(unexpected.nonEmpty)(s"unexpected keys: ${unexpected.mkString(", ")}").toVector ++
      mismatched ++
      Vector(s"Raw: ${observed.rawLine}")
  }

  private def describeAttributes(attributes: Vector[(String, SemanticValuePattern)]): String =
    attributes.map { case (name, value) => s"$name=$value" }.mkString("{", ", ", "}")
}
