package org.jetbrains.teamcity.plugins.sbt.logger.utils

import scala.collection.mutable

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
        failures ++= verifyPlainOutput(run.plainOutput, contract.plainOutput, delegated)
        failures ++= verifyProcessResult(run.exitCode, contract.processResult, delegated)
      case Right(prepared) =>
        failures ++= run.wireFailures
        failures ++= verifyServiceMessages(run.serviceMessages, prepared, run.wireFailures)
        failures ++= verifyPlainOutput(run.plainOutput, prepared.plainOutput, delegated)
        failures ++= verifyProcessResult(run.exitCode, prepared.processResult, delegated)
    }

    failures.result().distinct
  }

  private final case class MatchSolution(
    eventToObservation: Map[SemanticEventId, Int],
    bindings: Map[SemanticBindingKey, String]
  )

  private final case class SearchResult(solutions: Vector[MatchSolution], budgetExceeded: Boolean)

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
    failures ++= cardinalityFailures(observed, contract, wireFailures)
    failures ++= attributeFailures(observed, contract)
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
    if (observed.size == contract.events.size) {
      val exact = search(observed, contract, OwnershipMode.Enforce)
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

    val effectiveMapping = fullMapping.getOrElse(
      MatchSolution(safePartialMapping(observed, contract), Map.empty)
    )
    val dependentBlockedReason = assignmentBlockedReason.orElse(
      Option.when(unresolvedAssignment)("no complete semantic event assignment exists")
    )
    val bindingFailures = bindingConflictFailures(effectiveMapping.eventToObservation, observed, contract)
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
      wireFailures,
      dependentBlockedReason
    )
    if (unresolvedAssignment && bindingFailures.isEmpty) {
      failures += SbtSemanticFailure(
        SbtVerificationFailureCategory.SemanticCardinalityFailure,
        "Expected and observed messages cannot be assigned one-to-one under the declared attribute contract.",
        diagnosticOverview(observed, contract.events),
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
    val kinds = (expectedByKind.keySet ++ observedByKind.keySet).toVector.sortBy(_.wireName)
    val deltas = kinds.flatMap { kind =>
      val expected = expectedByKind.getOrElse(kind, Vector.empty)
      val actual = observedByKind.getOrElse(kind, Vector.empty)
      Option.when(expected.size != actual.size)((kind, expected, actual))
    }

    deltas.map { case (kind, expected, actual) =>
      val isPotentiallyMissing = expected.size > actual.size
      val disposition =
        if (isPotentiallyMissing && wireFailures.nonEmpty) SbtFindingDisposition.Blocked
        else SbtFindingDisposition.Violation
      val summary =
        if (isPotentiallyMissing) s"Missing ${expected.size - actual.size} parsed ${kind.wireName} event(s)."
        else s"Observed ${actual.size - expected.size} extra parsed ${kind.wireName} event(s)."
      val context =
        if (isPotentiallyMissing) Vector(s"Expected semantic events: ${expected.map(_.id).mkString(", ")}") ++
          Option.when(wireFailures.nonEmpty)("Malformed lines may contain the unavailable event(s).").toVector
        else actual.map(describe)
      SbtSemanticFailure(
        SbtVerificationFailureCategory.SemanticCardinalityFailure,
        summary,
        context,
        disposition,
        s"kind:${kind.wireName}"
      )
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
    contract: PreparedSemanticContract
  ): Vector[SbtSemanticFailure] = {
    val occurrenceByIndex = occurrences(observed)
    contract.events.flatMap { event =>
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
            Vector(s"Expected attributes: ${describeAttributes(event.attributes)}") ++ raw,
            semanticIdentity = s"event:${event.id}"
          ))
        }
      }
    }
  }

  private def hasStructuralFailures(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract
  ): Boolean =
    cardinalityFailures(observed, contract, Vector.empty).nonEmpty || attributeFailures(observed, contract).nonEmpty

  private def search(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract,
    ownershipMode: OwnershipMode
  ): SearchResult = {
    val occurrenceByIndex = occurrences(observed)
    val solutions = mutable.ArrayBuffer.empty[MatchSolution]
    var visitedStates = 0
    var budgetExceeded = false

    def loop(
      remaining: Vector[ExpectedSemanticEvent],
      available: Set[Int],
      bindings: Map[SemanticBindingKey, String],
      mapping: Map[SemanticEventId, Int]
    ): Unit = {
      if (solutions.size >= 2 || budgetExceeded) return
      visitedStates += 1
      if (visitedStates > contract.matcherStateBudget) {
        budgetExceeded = true
        return
      }
      if (remaining.isEmpty) {
        if (available.isEmpty) solutions += MatchSolution(mapping, bindings)
        return
      }

      val alternatives = remaining.map { event =>
        val candidates = available.toVector.sorted.flatMap { index =>
          val occurrenceMatches = event.occurrence.forall(_ == occurrenceByIndex(index))
          Option.when(occurrenceMatches)(
            matchEvent(event, observed(index), bindings, contract.distinctBindings, ownershipMode).map(index -> _)
          ).flatten
        }
        event -> candidates
      }
      val (event, candidates) = alternatives.minBy(_._2.size)
      if (candidates.isEmpty) return
      val nextRemaining = remaining.filterNot(_.id == event.id)
      candidates.foreach { case (index, nextBindings) =>
        loop(nextRemaining, available - index, nextBindings, mapping.updated(event.id, index))
      }
    }

    loop(contract.events, observed.indices.toSet, Map.empty, Map.empty)
    SearchResult(solutions.toVector, budgetExceeded)
  }

  /**
   * Maps only events with one stable candidate and discards collisions where two expectations want the same source
   * event. This supports independent diagnostics after an unrelated lane has a missing or extra event.
   */
  private def safePartialMapping(
    observed: Vector[ObservedServiceMessage],
    contract: PreparedSemanticContract
  ): Map[SemanticEventId, Int] = {
    val occurrenceByIndex = occurrences(observed)
    val uniqueCandidates = contract.events.flatMap { event =>
      val candidates = observed.indices.filter { index =>
        event.occurrence.forall(_ == occurrenceByIndex(index)) &&
          matchEvent(event, observed(index), Map.empty, contract.distinctBindings, OwnershipMode.Ignore).isDefined
      }
      Option.when(candidates.size == 1)(event.id -> candidates.head)
    }
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
    contract: PreparedSemanticContract
  ): Vector[SbtSemanticFailure] = {
    val values = mutable.HashMap.empty[SemanticBindingKey, Vector[CapturedBindingValue]].withDefaultValue(Vector.empty)
    contract.events.foreach { event =>
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
    wireFailures: Vector[SbtSemanticFailure],
    assignmentBlockedReason: Option[String]
  ): Vector[SbtSemanticFailure] = {
    val uses = contract.events.flatMap { event =>
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
    case SemanticValuePattern.AnyValue => Some(bindings)
    case SemanticValuePattern.NonEmpty => Option.when(value.nonEmpty)(bindings)
    case SemanticValuePattern.Bound(key) => bind(key, value, bindings, distinctBindings, ownershipMode)
    case SemanticValuePattern.Embedded(prefix, key, suffix) =>
      Option.when(value.startsWith(prefix) && value.endsWith(suffix) && value.length >= prefix.length + suffix.length) {
        value.substring(prefix.length, value.length - suffix.length)
      }.flatMap(captured => bind(key, captured, bindings, distinctBindings, ownershipMode))
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
  ): Vector[SbtSemanticFailure] = contract.happensBefore.toVector
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
            s"Required happens-before edge '${edge.before}' -> '${edge.after}' is reversed.",
            Vector(describe(before), describe(after)),
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
    case PlainOutputContract.DelegatedToHybrid if delegated.plainOutputVerified => Vector.empty
    case PlainOutputContract.DelegatedToHybrid => Vector(SbtSemanticFailure(
      SbtVerificationFailureCategory.PlainOutputFailure,
      "Plain-output verification is delegated to Hybrid mode, but the caller supplied no verification evidence.",
      disposition = SbtFindingDisposition.Blocked,
      semanticIdentity = "plain-output"
    ))
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
