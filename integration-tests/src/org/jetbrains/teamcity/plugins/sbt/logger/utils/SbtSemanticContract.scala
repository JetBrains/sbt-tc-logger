package org.jetbrains.teamcity.plugins.sbt.logger.utils

import scala.collection.mutable

private[logger] final case class SemanticEventId(value: String) {
  override def toString: String = value
}

private[logger] object SemanticEventId {
  def named(value: String): SemanticEventId = SemanticEventId(value)
}

/** Runtime validation and diagnostic role for a named semantic binding. */
private[logger] sealed trait SemanticBindingKind {
  def displayName: String
  def accepts(value: String): Boolean
  def isOwnership: Boolean = false
}

private[logger] object SemanticBindingKind {
  case object Flow extends SemanticBindingKind {
    val displayName = "flow"
    def accepts(value: String): Boolean = value.nonEmpty
    override val isOwnership = true
  }
  case object BuildId extends SemanticBindingKind {
    val displayName = "build id"
    def accepts(value: String): Boolean = value.matches("-?[0-9]+")
    override val isOwnership = true
  }
  case object Path extends SemanticBindingKind {
    val displayName = "path"
    def accepts(value: String): Boolean = value.nonEmpty
  }
  case object DurationMillis extends SemanticBindingKind {
    val displayName = "duration in milliseconds"
    def accepts(value: String): Boolean = value.matches("[0-9]+")
  }
  case object Value extends SemanticBindingKind {
    val displayName = "value"
    def accepts(value: String): Boolean = value.nonEmpty
  }
}

private[logger] final case class SemanticBindingKey private(name: String, kind: SemanticBindingKind) {
  override def toString: String = s"${kind.displayName}:$name"
}

private[logger] object SemanticBindingKey {
  def flow(name: String): SemanticBindingKey = SemanticBindingKey(name, SemanticBindingKind.Flow)
  def buildId(name: String): SemanticBindingKey = SemanticBindingKey(name, SemanticBindingKind.BuildId)
  def path(name: String): SemanticBindingKey = SemanticBindingKey(name, SemanticBindingKind.Path)
  def duration(name: String): SemanticBindingKey = SemanticBindingKey(name, SemanticBindingKind.DurationMillis)
  def value(name: String): SemanticBindingKey = SemanticBindingKey(name, SemanticBindingKind.Value)
}

/** A deliberately finite set of attribute patterns; arbitrary regexes are not part of the semantic contract. */
private[logger] sealed trait SemanticValuePattern

private[logger] object SemanticValuePattern {
  final case class Exact(value: String) extends SemanticValuePattern
  case object AnyValue extends SemanticValuePattern
  case object NonEmpty extends SemanticValuePattern
  final case class Bound(key: SemanticBindingKey) extends SemanticValuePattern

  /** Captures or reuses one typed value surrounded by stable literal text. */
  final case class Embedded(prefix: String, key: SemanticBindingKey, suffix: String) extends SemanticValuePattern

  def exact(value: String): SemanticValuePattern = Exact(value)
  def any: SemanticValuePattern = AnyValue
  def nonEmpty: SemanticValuePattern = NonEmpty
  def bound(key: SemanticBindingKey): SemanticValuePattern = Bound(key)
  def embedded(prefix: String, key: SemanticBindingKey, suffix: String): SemanticValuePattern = Embedded(prefix, key, suffix)
}

private[logger] final case class ExpectedSemanticEvent(
  id: SemanticEventId,
  kind: ObservedServiceMessageKind,
  attributes: Vector[(String, SemanticValuePattern)],
  /** One-based ordinal among all observed messages of `kind`; use only when duplicate occurrences are intentional. */
  occurrence: Option[Int] = None
)

private[logger] object ExpectedSemanticEvent {
  def apply(
    id: String,
    kind: ObservedServiceMessageKind,
    attributes: (String, SemanticValuePattern)*
  ): ExpectedSemanticEvent = ExpectedSemanticEvent(SemanticEventId(id), kind, attributes.toVector)

  def occurrence(
    id: String,
    kind: ObservedServiceMessageKind,
    ordinal: Int,
    attributes: (String, SemanticValuePattern)*
  ): ExpectedSemanticEvent = ExpectedSemanticEvent(SemanticEventId(id), kind, attributes.toVector, Some(ordinal))
}

private[logger] final case class HappensBefore(before: SemanticEventId, after: SemanticEventId)

private[logger] object HappensBefore {
  def apply(before: String, after: String): HappensBefore =
    HappensBefore(SemanticEventId(before), SemanticEventId(after))
}

private[logger] final case class DistinctSemanticBindings(first: SemanticBindingKey, second: SemanticBindingKey)

/**
 * Reusable lifecycle structure. A rule adds start-before-members-before-finish edges and requires one flow binding
 * on every participating event. No edge is added to any other lifecycle.
 */
private[logger] sealed trait SemanticLifecycleRule {
  def name: String
  def start: SemanticEventId
  def members: Vector[SemanticEventId]
  def finish: SemanticEventId
  def flow: SemanticBindingKey
  def startKind: ObservedServiceMessageKind
  def finishKind: ObservedServiceMessageKind
}

private[logger] object SemanticLifecycleRule {
  private final case class Rule(
    name: String,
    start: SemanticEventId,
    members: Vector[SemanticEventId],
    finish: SemanticEventId,
    flow: SemanticBindingKey,
    startKind: ObservedServiceMessageKind,
    finishKind: ObservedServiceMessageKind
  ) extends SemanticLifecycleRule

  def compilation(
    name: String,
    start: String,
    members: Seq[String],
    finish: String,
    flow: SemanticBindingKey
  ): SemanticLifecycleRule = Rule(
    name,
    SemanticEventId(start),
    members.map(SemanticEventId.apply).toVector,
    SemanticEventId(finish),
    flow,
    ObservedServiceMessageKind.CompilationStarted,
    ObservedServiceMessageKind.CompilationFinished
  )

  def suite(
    name: String,
    start: String,
    ownedEvents: Seq[String],
    finish: String,
    flow: SemanticBindingKey
  ): SemanticLifecycleRule = Rule(
    name,
    SemanticEventId(start),
    ownedEvents.map(SemanticEventId.apply).toVector,
    SemanticEventId(finish),
    flow,
    ObservedServiceMessageKind.TestSuiteStarted,
    ObservedServiceMessageKind.TestSuiteFinished
  )

  def test(
    name: String,
    start: String,
    outcomes: Seq[String],
    finish: String,
    flow: SemanticBindingKey
  ): SemanticLifecycleRule = Rule(
    name,
    SemanticEventId(start),
    outcomes.map(SemanticEventId.apply).toVector,
    SemanticEventId(finish),
    flow,
    ObservedServiceMessageKind.TestStarted,
    ObservedServiceMessageKind.TestFinished
  )

  def block(
    name: String,
    open: String,
    members: Seq[String],
    close: String,
    flow: SemanticBindingKey
  ): SemanticLifecycleRule = Rule(
    name,
    SemanticEventId(open),
    members.map(SemanticEventId.apply).toVector,
    SemanticEventId(close),
    flow,
    ObservedServiceMessageKind.BlockOpened,
    ObservedServiceMessageKind.BlockClosed
  )
}

private[logger] sealed trait PlainOutputContract

private[logger] object PlainOutputContract {
  /** Semantic mode is strict by default: any undeclared plain output fails. */
  case object RejectAll extends PlainOutputContract
  final case class Exact(lines: Vector[String]) extends PlainOutputContract
  final case class Declared(description: String, accepts: String => Boolean) extends PlainOutputContract

  /** The hybrid harness promises to verify every plain line with its exact transcript contract. */
  case object DelegatedToHybrid extends PlainOutputContract
}

private[logger] sealed trait ProcessResultContract

private[logger] object ProcessResultContract {
  case object DelegatedToHarness extends ProcessResultContract
  final case class ExitCode(expected: Int) extends ProcessResultContract
}

/** Evidence supplied by the future Hybrid/outer harness for explicitly delegated checks. */
private[logger] final case class SbtDelegatedVerification(
  plainOutputVerified: Boolean = false,
  processResultVerified: Boolean = false
)

private[logger] final case class SbtSemanticContract(
  events: Vector[ExpectedSemanticEvent],
  happensBefore: Set[HappensBefore] = Set.empty,
  lifecycles: Vector[SemanticLifecycleRule] = Vector.empty,
  distinctBindings: Set[DistinctSemanticBindings] = Set.empty,
  plainOutput: PlainOutputContract = PlainOutputContract.RejectAll,
  processResult: ProcessResultContract = ProcessResultContract.DelegatedToHarness,
  matcherStateBudget: Int = 100000
)

private[utils] final case class PreparedSemanticContract(
  events: Vector[ExpectedSemanticEvent],
  happensBefore: Set[HappensBefore],
  lifecycles: Vector[SemanticLifecycleRule],
  lifecycleEventIds: Set[SemanticEventId],
  lifecycleBoundaryKinds: Set[ObservedServiceMessageKind],
  distinctBindings: Set[DistinctSemanticBindings],
  plainOutput: PlainOutputContract,
  processResult: ProcessResultContract,
  matcherStateBudget: Int
)

private[utils] object SbtSemanticContractValidator {
  private val StableName = "[a-z][a-z0-9.-]*"

  def prepare(contract: SbtSemanticContract): Either[Vector[SbtSemanticFailure], PreparedSemanticContract] = {
    val problems = mutable.ArrayBuffer.empty[String]
    if (contract.events.isEmpty) problems += "A semantic contract must declare at least one service-message event."
    if (contract.matcherStateBudget <= 0) problems += s"Matcher state budget must be positive, got ${contract.matcherStateBudget}."

    contract.events.groupBy(_.id).foreach { case (id, values) =>
      if (values.size > 1) problems += s"Duplicate semantic event id '$id'."
    }
    contract.events.foreach { event =>
      if (!event.id.value.matches(StableName)) {
        problems += s"Invalid semantic event id '${event.id}' (expected name pattern: $StableName)."
      }
      event.attributes.groupBy(_._1).foreach { case (name, values) =>
        if (values.size > 1) problems += s"Event '${event.id}' declares attribute '$name' more than once."
      }
      event.occurrence.filter(_ <= 0).foreach { value =>
        problems += s"Event '${event.id}' has non-positive occurrence $value."
      }
      event.attributes.foreach { case (attribute, pattern) =>
        if (attribute.isEmpty) problems += s"Event '${event.id}' declares an empty attribute name."
        bindingKeys(pattern).filter(key => !key.name.matches(StableName)).foreach { key =>
          problems += s"Invalid ${key.kind.displayName} binding name '${key.name}' in event '${event.id}'."
        }
      }
    }

    val byId = contract.events.map(event => event.id -> event).toMap
    var expandedEvents = contract.events
    val lifecycleEdges = mutable.HashSet.empty[HappensBefore]
    val lifecycleEventIds = mutable.HashSet.empty[SemanticEventId]
    val lifecycleBoundaryKinds = mutable.HashSet.empty[ObservedServiceMessageKind]

    contract.lifecycles.foreach { lifecycle =>
      val lifecycleProblems = mutable.ArrayBuffer.empty[String]
      if (!lifecycle.name.matches(StableName)) lifecycleProblems += s"Invalid lifecycle name '${lifecycle.name}'."
      if (!lifecycle.flow.name.matches(StableName)) {
        lifecycleProblems += s"Invalid flow binding name '${lifecycle.flow.name}' in lifecycle '${lifecycle.name}'."
      }
      if (lifecycle.flow.kind != SemanticBindingKind.Flow) {
        lifecycleProblems += s"Lifecycle '${lifecycle.name}' requires a flow binding, got ${lifecycle.flow.kind.displayName} '${lifecycle.flow.name}'."
      }
      if (lifecycle.start == lifecycle.finish) lifecycleProblems += s"Lifecycle '${lifecycle.name}' uses one event as both start and finish."
      if (lifecycle.members.distinct.size != lifecycle.members.size) lifecycleProblems += s"Lifecycle '${lifecycle.name}' contains duplicate members."

      val ids = lifecycle.start +: lifecycle.members :+ lifecycle.finish
      ids.filterNot(byId.contains).foreach { id =>
        lifecycleProblems += s"Lifecycle '${lifecycle.name}' references unknown event '$id'."
      }
      byId.get(lifecycle.start).filter(_.kind != lifecycle.startKind).foreach { _ =>
        lifecycleProblems += s"Lifecycle '${lifecycle.name}' start '${lifecycle.start}' must be ${lifecycle.startKind.wireName}."
      }
      byId.get(lifecycle.finish).filter(_.kind != lifecycle.finishKind).foreach { _ =>
        lifecycleProblems += s"Lifecycle '${lifecycle.name}' finish '${lifecycle.finish}' must be ${lifecycle.finishKind.wireName}."
      }

      if (lifecycleProblems.isEmpty) {
        val expanded = ids.map { id =>
          val eventIndex = expandedEvents.indexWhere(_.id == id)
          withFlow(expandedEvents(eventIndex), lifecycle.flow).map(eventIndex -> _)
        }
        expanded.collect { case Left(message) => message }.foreach { message =>
          lifecycleProblems += s"Lifecycle '${lifecycle.name}' $message"
        }
        if (lifecycleProblems.isEmpty) {
          expanded.collect { case Right((eventIndex, event)) => eventIndex -> event }.foreach { case (eventIndex, event) =>
            expandedEvents = expandedEvents.updated(eventIndex, event)
          }
          lifecycleEventIds ++= ids
          lifecycleBoundaryKinds ++= Set(lifecycle.startKind, lifecycle.finishKind)
          lifecycleEdges += HappensBefore(lifecycle.start, lifecycle.finish)
          lifecycle.members.foreach { member =>
            lifecycleEdges += HappensBefore(lifecycle.start, member)
            lifecycleEdges += HappensBefore(member, lifecycle.finish)
          }
        }
      }
      problems ++= lifecycleProblems
    }

    val allEdges = contract.happensBefore ++ lifecycleEdges
    allEdges.foreach { edge =>
      if (!byId.contains(edge.before)) problems += s"Ordering edge references unknown event '${edge.before}'."
      if (!byId.contains(edge.after)) problems += s"Ordering edge references unknown event '${edge.after}'."
      if (edge.before == edge.after) problems += s"Ordering edge for '${edge.before}' is self-referential."
    }
    if (allEdges.forall(edge => byId.contains(edge.before) && byId.contains(edge.after))) {
      findCycle(byId.keySet, allEdges).foreach { cycle =>
        problems += s"Semantic happens-before graph contains a cycle: ${cycle.mkString(" -> ")}."
      }
    }

    val capturedKeys = expandedEvents.iterator.flatMap(_.attributes.iterator.flatMap { case (_, pattern) =>
      bindingKeys(pattern)
    }).toSet
    contract.distinctBindings.foreach { pair =>
      if (pair.first == pair.second) problems += s"Binding '${pair.first}' cannot be distinct from itself."
      Seq(pair.first, pair.second).filter(key => !key.name.matches(StableName)).foreach { key =>
        problems += s"Invalid ${key.kind.displayName} binding name '${key.name}'."
      }
      Seq(pair.first, pair.second).filterNot(capturedKeys.contains).foreach { key =>
        problems += s"Distinct binding '$key' is not captured by any expanded expected event."
      }
    }

    if (problems.nonEmpty) Left(problems.distinct.toVector.map { summary =>
      SbtSemanticFailure(
        SbtVerificationFailureCategory.GoldenSyntaxFailure,
        summary,
        semanticIdentity = "contract"
      )
    })
    else Right(PreparedSemanticContract(
      events = expandedEvents,
      happensBefore = allEdges,
      lifecycles = contract.lifecycles,
      lifecycleEventIds = lifecycleEventIds.toSet,
      lifecycleBoundaryKinds = lifecycleBoundaryKinds.toSet,
      distinctBindings = contract.distinctBindings,
      plainOutput = contract.plainOutput,
      processResult = contract.processResult,
      matcherStateBudget = contract.matcherStateBudget
    ))
  }

  private def bindingKeys(pattern: SemanticValuePattern): Vector[SemanticBindingKey] = pattern match {
    case SemanticValuePattern.Bound(key) => Vector(key)
    case SemanticValuePattern.Embedded(_, key, _) => Vector(key)
    case _ => Vector.empty
  }

  private def withFlow(
    event: ExpectedSemanticEvent,
    flow: SemanticBindingKey
  ): Either[String, ExpectedSemanticEvent] = event.attributes.find(_._1 == "flowId") match {
    case None => Right(event.copy(attributes = event.attributes :+ ("flowId" -> SemanticValuePattern.Bound(flow))))
    case Some((_, SemanticValuePattern.Bound(existing))) if existing == flow => Right(event)
    case Some(_) => Left(s"event '${event.id}' must bind attribute 'flowId' to '$flow'.")
  }

  private def findCycle(
    nodes: Set[SemanticEventId],
    edges: Set[HappensBefore]
  ): Option[Vector[SemanticEventId]] = {
    val outgoing = edges.groupMap(_.before)(_.after).withDefaultValue(Set.empty)
    val visiting = mutable.HashSet.empty[SemanticEventId]
    val visited = mutable.HashSet.empty[SemanticEventId]

    def visit(node: SemanticEventId, path: Vector[SemanticEventId]): Option[Vector[SemanticEventId]] = {
      if (visiting.contains(node)) {
        val start = path.indexOf(node)
        Some(path.drop(start) :+ node)
      } else if (visited.contains(node)) {
        None
      } else {
        visiting += node
        val nextNodes = outgoing(node).iterator
        var result = Option.empty[Vector[SemanticEventId]]
        while (nextNodes.hasNext && result.isEmpty) {
          result = visit(nextNodes.next(), path :+ node)
        }
        visiting -= node
        visited += node
        result
      }
    }

    val remaining = nodes.iterator
    var result = Option.empty[Vector[SemanticEventId]]
    while (remaining.hasNext && result.isEmpty) result = visit(remaining.next(), Vector.empty)
    result
  }
}
