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
  case object DurationSeconds extends SemanticBindingKind {
    val displayName = "duration in seconds"
    def accepts(value: String): Boolean = value.matches("[0-9]+(?:\\.[0-9]+)?")
  }
  case object LogbackThread extends SemanticBindingKind {
    val displayName = "Logback thread"
    def accepts(value: String): Boolean = value.matches("pool-[0-9]+-thread-[0-9]+")
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
  def durationSeconds(name: String): SemanticBindingKey = SemanticBindingKey(name, SemanticBindingKind.DurationSeconds)
  def logbackThread(name: String): SemanticBindingKey = SemanticBindingKey(name, SemanticBindingKind.LogbackThread)
  def value(name: String): SemanticBindingKey = SemanticBindingKey(name, SemanticBindingKind.Value)
}

/** A deliberately finite set of attribute patterns; arbitrary regexes are not part of the semantic contract. */
private[logger] sealed trait SemanticValuePattern

private[logger] object SemanticValuePattern {
  final case class Exact(value: String) extends SemanticValuePattern
  final case class Bound(key: SemanticBindingKey) extends SemanticValuePattern

  /** Captures or reuses one typed value surrounded by stable literal text. */
  final case class Embedded(prefix: String, key: SemanticBindingKey, suffix: String) extends SemanticValuePattern

  /** A non-negative integer or decimal duration without a unit. */
  case object UnsignedDuration extends SemanticValuePattern

  /** One logger-owned dependency-resource line with an exact source and URL. */
  final case class DependencyResource(
    project: String,
    configuration: String,
    url: String,
    outcomes: Set[DependencyResourceOutcome]
  ) extends SemanticValuePattern

  /** Stable resolution failure data with a reusable Ivy home and bounded dependency-owned implementation frames. */
  final case class DependencyResolveFailure(
    coordinate: String,
    repositoryUrl: String,
    ivyHome: SemanticBindingKey,
    taskScoped: Boolean,
    maximumInternalFrames: Int
  ) extends SemanticValuePattern

  /** Exact detailed-resolution outcome counts with only the dependency-owned duration treated structurally. */
  final case class DependencyResolutionSummary(
    localCacheHits: Int,
    downloads: Int,
    failedDownloadAttempts: Int,
    updateReportCacheHits: Int
  ) extends SemanticValuePattern

  /** The cold compiler-bridge announcement. Module and Scala versions are validated structurally. */
  case object CompilerBridgeAnnouncement extends SemanticValuePattern

  /** The cold compiler-bridge completion line with a typed seconds duration. */
  case object CompilerBridgeCompletion extends SemanticValuePattern

  /** One dependency-owned SBT/Zinc debug sentence from a finite structural family. */
  final case class StructuredSbtDebug(kind: StructuredSbtDebugKind) extends SemanticValuePattern

  /** One JDK 17 reflection frame from the finite task-failure rendering emitted by SBT 1. */
  final case class JdkMethodReflectionFrame(role: JdkMethodReflectionFrameRole) extends SemanticValuePattern

  /** Exact user-owned failure content surrounded by a finite, bounded set of recognized framework frames. */
  final case class UserFailure(
    prefix: String,
    userFrames: Vector[String],
    framework: RecognizedTestFramework,
    maximumFrameworkFrames: Int,
    allowedGeneratedOwners: Set[String]
  ) extends SemanticValuePattern

  /**
   * One exact top-level throwable and one exact cause, rendered as build-log text whose every physical line has
   * the same literal prefix. Required user frames are line remainders after removing that prefix. Every section
   * also needs bounded, finite internal-tail evidence; line/source metadata on those internal frames is structural.
   */
  final case class LinePrefixedThrowableChain(
    linePrefix: String,
    topException: String,
    topMessage: String,
    requiredTopUserFrames: Vector[String],
    causeException: String,
    causeMessage: String,
    requiredCauseUserFrames: Vector[String],
    framework: RecognizedTestFramework,
    maximumRecognizedFrames: Int
  ) extends SemanticValuePattern

  def exact(value: String): SemanticValuePattern = Exact(value)
  def bound(key: SemanticBindingKey): SemanticValuePattern = Bound(key)
  def embedded(prefix: String, key: SemanticBindingKey, suffix: String): SemanticValuePattern = Embedded(prefix, key, suffix)
  def unsignedDuration: SemanticValuePattern = UnsignedDuration
  def dependencyResource(
    project: String,
    configuration: String,
    url: String,
    outcomes: Set[DependencyResourceOutcome]
  ): SemanticValuePattern = DependencyResource(project, configuration, url, outcomes)
  def dependencyResolveFailure(
    coordinate: String,
    repositoryUrl: String,
    ivyHome: SemanticBindingKey,
    taskScoped: Boolean,
    maximumInternalFrames: Int = 32
  ): SemanticValuePattern =
    DependencyResolveFailure(coordinate, repositoryUrl, ivyHome, taskScoped, maximumInternalFrames)
  def dependencyResolutionSummary(
    localCacheHits: Int,
    downloads: Int,
    failedDownloadAttempts: Int,
    updateReportCacheHits: Int
  ): SemanticValuePattern = DependencyResolutionSummary(
    localCacheHits,
    downloads,
    failedDownloadAttempts,
    updateReportCacheHits
  )
  def compilerBridgeAnnouncement: SemanticValuePattern = CompilerBridgeAnnouncement
  def compilerBridgeCompletion: SemanticValuePattern = CompilerBridgeCompletion
  def structuredSbtDebug(kind: StructuredSbtDebugKind): SemanticValuePattern = StructuredSbtDebug(kind)
  def jdkMethodReflectionFrame(role: JdkMethodReflectionFrameRole): SemanticValuePattern =
    JdkMethodReflectionFrame(role)
  def userFailure(
    prefix: String,
    userFrames: Seq[String],
    framework: RecognizedTestFramework,
    maximumFrameworkFrames: Int = 64,
    allowedGeneratedOwners: Set[String] = Set.empty
  ): SemanticValuePattern =
    UserFailure(prefix, userFrames.toVector, framework, maximumFrameworkFrames, allowedGeneratedOwners)
  def linePrefixedThrowableChain(
    linePrefix: String,
    topException: String,
    topMessage: String,
    requiredTopUserFrames: Seq[String],
    causeException: String,
    causeMessage: String,
    requiredCauseUserFrames: Seq[String],
    framework: RecognizedTestFramework,
    maximumRecognizedFrames: Int = 64
  ): SemanticValuePattern = LinePrefixedThrowableChain(
    linePrefix,
    topException,
    topMessage,
    requiredTopUserFrames.toVector,
    causeException,
    causeMessage,
    requiredCauseUserFrames.toVector,
    framework,
    maximumRecognizedFrames
  )
}

private[logger] enum StructuredSbtDebugKind(val id: String) {
  case DependencyCheck extends StructuredSbtDebugKind("dependency-check")
  case DependencyUpdate extends StructuredSbtDebugKind("dependency-update")
  case DependencyDone extends StructuredSbtDebugKind("dependency-done")
  case IncrementalHeader extends StructuredSbtDebugKind("incremental-header")
  case IncrementalCompile extends StructuredSbtDebugKind("incremental-compile")
  case PreviousStamps extends StructuredSbtDebugKind("previous-stamps")
  case CurrentSources extends StructuredSbtDebugKind("current-sources")
  case InitialChanges extends StructuredSbtDebugKind("initial-changes")
  case FullCompilation extends StructuredSbtDebugKind("full-compilation")
  case InvalidatedSources extends StructuredSbtDebugKind("invalidated-sources")
  case InitialIncludedNodes extends StructuredSbtDebugKind("initial-included-nodes")
  case RecompileAllSources extends StructuredSbtDebugKind("recompile-all-sources")
  case CompilationCycle extends StructuredSbtDebugKind("compilation-cycle")
  case CompilerBridgeRetrieval extends StructuredSbtDebugKind("compiler-bridge-retrieval")
  case CachedCompiler extends StructuredSbtDebugKind("cached-compiler")
  case CompilerArguments extends StructuredSbtDebugKind("compiler-arguments")
  case CompilationFailed extends StructuredSbtDebugKind("compilation-failed")
  case CreatedClassFileManager extends StructuredSbtDebugKind("created-class-file-manager")
  case AboutToDeleteClassFiles extends StructuredSbtDebugKind("about-to-delete-class-files")
  case BackupClassFiles extends StructuredSbtDebugKind("backup-class-files")
  case RollbackClassFiles extends StructuredSbtDebugKind("rollback-class-files")
  case RemoveGeneratedClasses extends StructuredSbtDebugKind("remove-generated-classes")
  case RestoreClassFiles extends StructuredSbtDebugKind("restore-class-files")
  case RemoveTemporaryDirectory extends StructuredSbtDebugKind("remove-temporary-directory")
  case WroteProducts extends StructuredSbtDebugKind("wrote-products")
}

private[logger] enum JdkMethodReflectionFrameRole(val id: String) {
  case NativeAccessorInvoke0 extends JdkMethodReflectionFrameRole("native-accessor-invoke0")
  case NativeAccessorInvoke extends JdkMethodReflectionFrameRole("native-accessor-invoke")
  case DelegatingAccessorInvoke extends JdkMethodReflectionFrameRole("delegating-accessor-invoke")
  case MethodInvoke extends JdkMethodReflectionFrameRole("method-invoke")
}

private[logger] enum DependencyResourceOutcome {
  case LocalCacheHit
  case Downloaded
  case FailedDownloadAttempt
}

private[logger] enum RecognizedTestFramework {
  case ScalaTest
  case Specs2
  case JUnit
}

private[logger] final case class ExpectedSemanticEvent(
  id: SemanticEventId,
  kind: ObservedServiceMessageKind,
  attributes: Vector[(String, SemanticValuePattern)],
  /** One-based ordinal among all observed messages of `kind`; use only when duplicate occurrences are intentional. */
  occurrence: Option[Int] = None,
  /** Number of structurally equivalent events; clone assignment is canonicalized by source order. */
  multiplicity: Int = 1
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

  def repeated(
    id: String,
    kind: ObservedServiceMessageKind,
    multiplicity: Int,
    attributes: (String, SemanticValuePattern)*
  ): ExpectedSemanticEvent =
    ExpectedSemanticEvent(SemanticEventId(id), kind, attributes.toVector, multiplicity = multiplicity)
}

private[logger] final case class HappensBefore(before: SemanticEventId, after: SemanticEventId)

private[logger] object HappensBefore {
  def apply(before: String, after: String): HappensBefore =
    HappensBefore(SemanticEventId(before), SemanticEventId(after))
}

private[logger] final case class DistinctSemanticBindings(first: SemanticBindingKey, second: SemanticBindingKey)

/** An optional event block is legal only when absent or completely consumed. */
private[logger] final case class OptionalSemanticEventGroup(
  name: String,
  events: Vector[ExpectedSemanticEvent],
  happensBefore: Set[HappensBefore] = Set.empty,
  ownership: Option[SemanticValuePattern] = None
)

/**
 * Reusable lifecycle structure. A rule adds start-before-members-before-finish edges and requires one shared
 * flow/build ownership pattern on every participating event. No edge is added to any other lifecycle.
 */
private[logger] sealed trait SemanticLifecycleRule {
  def name: String
  def start: SemanticEventId
  def members: Vector[SemanticEventId]
  def finish: SemanticEventId
  def ownership: SemanticValuePattern
  def startKind: ObservedServiceMessageKind
  def finishKind: ObservedServiceMessageKind
}

private[logger] object SemanticLifecycleRule {
  private final case class Rule(
    name: String,
    start: SemanticEventId,
    members: Vector[SemanticEventId],
    finish: SemanticEventId,
    ownership: SemanticValuePattern,
    startKind: ObservedServiceMessageKind,
    finishKind: ObservedServiceMessageKind
  ) extends SemanticLifecycleRule

  def compilation(
    name: String,
    start: String,
    members: Seq[String],
    finish: String,
    flow: SemanticBindingKey
  ): SemanticLifecycleRule = compilation(name, start, members, finish, SemanticValuePattern.Bound(flow))

  def compilation(
    name: String,
    start: String,
    members: Seq[String],
    finish: String,
    ownership: SemanticValuePattern
  ): SemanticLifecycleRule = Rule(
    name,
    SemanticEventId(start),
    members.map(SemanticEventId.apply).toVector,
    SemanticEventId(finish),
    ownership,
    ObservedServiceMessageKind.CompilationStarted,
    ObservedServiceMessageKind.CompilationFinished
  )

  def suite(
    name: String,
    start: String,
    ownedEvents: Seq[String],
    finish: String,
    flow: SemanticBindingKey
  ): SemanticLifecycleRule = suite(name, start, ownedEvents, finish, SemanticValuePattern.Bound(flow))

  def suite(
    name: String,
    start: String,
    ownedEvents: Seq[String],
    finish: String,
    ownership: SemanticValuePattern
  ): SemanticLifecycleRule = Rule(
    name,
    SemanticEventId(start),
    ownedEvents.map(SemanticEventId.apply).toVector,
    SemanticEventId(finish),
    ownership,
    ObservedServiceMessageKind.TestSuiteStarted,
    ObservedServiceMessageKind.TestSuiteFinished
  )

  def test(
    name: String,
    start: String,
    outcomes: Seq[String],
    finish: String,
    flow: SemanticBindingKey
  ): SemanticLifecycleRule = test(name, start, outcomes, finish, SemanticValuePattern.Bound(flow))

  def test(
    name: String,
    start: String,
    outcomes: Seq[String],
    finish: String,
    ownership: SemanticValuePattern
  ): SemanticLifecycleRule = Rule(
    name,
    SemanticEventId(start),
    outcomes.map(SemanticEventId.apply).toVector,
    SemanticEventId(finish),
    ownership,
    ObservedServiceMessageKind.TestStarted,
    ObservedServiceMessageKind.TestFinished
  )

  def block(
    name: String,
    open: String,
    members: Seq[String],
    close: String,
    flow: SemanticBindingKey
  ): SemanticLifecycleRule = block(name, open, members, close, SemanticValuePattern.Bound(flow))

  def block(
    name: String,
    open: String,
    members: Seq[String],
    close: String,
    ownership: SemanticValuePattern
  ): SemanticLifecycleRule = Rule(
    name,
    SemanticEventId(open),
    members.map(SemanticEventId.apply).toVector,
    SemanticEventId(close),
    ownership,
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
  /** An exact ordered sequence whose values use only named finite line patterns. */
  final case class Patterns(patterns: Vector[PlainOutputPattern]) extends PlainOutputContract

  /** The hybrid harness promises to verify every plain line with its exact transcript contract. */
  case object DelegatedToHybrid extends PlainOutputContract
}

private[logger] sealed trait PlainOutputPattern

private[logger] object PlainOutputPattern {
  final case class Exact(line: String) extends PlainOutputPattern
  case object SbtTaskSummary extends PlainOutputPattern
  final case class SbtDebug(kind: RawSbtDebugKind) extends PlainOutputPattern
  /** One compiler-owned info line with an exact target suffix and a variable non-empty workspace prefix. */
  final case class CompilerCompileInfo(workspace: SemanticBindingKey, targetSuffix: String) extends PlainOutputPattern
  /** One raw compiler diagnostic whose file, line, severity, and text must agree with one parsed inspection. */
  final case class CompilerInspectionDiagnostic(
    workspace: SemanticBindingKey,
    sourceSuffix: String,
    level: CompilerDiagnosticLevel,
    column: Int
  ) extends PlainOutputPattern
  /** One exact ScalaTest Logback line with a reusable dynamic pool thread. */
  final case class ScalaTestLogbackLine(
    thread: SemanticBindingKey,
    level: LogbackLevel,
    exactSuffix: String
  ) extends PlainOutputPattern
  /** The line must precede every parsed TeamCity service message in the bounded transcript. */
  final case class BeforeServiceMessages(pattern: PlainOutputPattern) extends PlainOutputPattern
  /** The line must follow every parsed TeamCity service message in the bounded transcript. */
  final case class AfterServiceMessages(pattern: PlainOutputPattern) extends PlainOutputPattern
  /** The line is inside the selected exact-named suite invocation and precedes that invocation's first test. */
  final case class BeforeFirstTestInSuite(
    suiteName: String,
    invocationOrdinal: Int,
    pattern: PlainOutputPattern
  ) extends PlainOutputPattern
  case object CompilerBridgeAnnouncement extends PlainOutputPattern
  case object CompilerBridgeCompletion extends PlainOutputPattern
  /** Every child line is consumed, or none is. Nested optional groups are rejected. */
  final case class OptionalGroup(name: String, patterns: Vector[PlainOutputPattern]) extends PlainOutputPattern
}

private[logger] enum LogbackLevel(val rawName: String) {
  case Warn extends LogbackLevel("WARN")
  case Error extends LogbackLevel("ERROR")
}

private[logger] enum CompilerDiagnosticLevel(val rawName: String, val inspectionSeverity: String) {
  case Error extends CompilerDiagnosticLevel("error", "ERROR")
  case Warning extends CompilerDiagnosticLevel("warn", "WARNING")
}

private[logger] enum RawSbtDebugKind {
  case CommandExecution
  case TaskEvaluation
  case TaskRun
}

private[logger] sealed trait ProcessResultContract

private[logger] object ProcessResultContract {
  case object DelegatedToHarness extends ProcessResultContract
  case object Success extends ProcessResultContract
  case object Failure extends ProcessResultContract
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
  optionalGroups: Vector[OptionalSemanticEventGroup] = Vector.empty,
  distinctBindings: Set[DistinctSemanticBindings] = Set.empty,
  plainOutput: PlainOutputContract = PlainOutputContract.RejectAll,
  processResult: ProcessResultContract = ProcessResultContract.DelegatedToHarness,
  matcherStateBudget: Int = 100000
)

private[utils] final case class PreparedSemanticLifecycle(
  name: String,
  start: SemanticEventId,
  members: Vector[SemanticEventId],
  finish: SemanticEventId
)

private[utils] final case class PreparedOptionalSemanticEventGroup(
  name: String,
  events: Vector[ExpectedSemanticEvent],
  happensBefore: Set[HappensBefore],
  requiresAdjacency: Boolean
)

private[utils] final case class PreparedSemanticContract(
  events: Vector[ExpectedSemanticEvent],
  happensBefore: Set[HappensBefore],
  lifecycles: Vector[PreparedSemanticLifecycle],
  optionalGroups: Vector[PreparedOptionalSemanticEventGroup],
  lifecycleEventIds: Set[SemanticEventId],
  lifecycleBoundaryKinds: Set[ObservedServiceMessageKind],
  canonicalPrevious: Map[SemanticEventId, SemanticEventId],
  distinctBindings: Set[DistinctSemanticBindings],
  plainOutput: PlainOutputContract,
  processResult: ProcessResultContract,
  matcherStateBudget: Int
)

private[utils] object SbtSemanticContractValidator {
  private val StableName = "[a-z][a-z0-9.-]*"
  private val FullyQualifiedJvmOwner = "(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*"
  private val ThrowableClass = "(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*"
  private val ExactStackFrame = "[ \\t]+at [^\\r\\n()]+\\([^\\r\\n()]+\\)"

  def prepare(contract: SbtSemanticContract): Either[Vector[SbtSemanticFailure], PreparedSemanticContract] = {
    val problems = mutable.ArrayBuffer.empty[String]
    if (contract.matcherStateBudget <= 0) problems += s"Matcher state budget must be positive, got ${contract.matcherStateBudget}."

    val requiredPrototypeIds = contract.events.map(_.id).toSet
    val prototypes = contract.events ++ contract.optionalGroups.flatMap(_.events)
    prototypes.groupBy(_.id).foreach { case (id, values) =>
      if (values.size > 1) problems += s"Duplicate semantic event id '$id'."
    }
    prototypes.foreach { event =>
      if (!event.id.value.matches(StableName)) {
        problems += s"Invalid semantic event id '${event.id}' (expected name pattern: $StableName)."
      }
      event.attributes.groupBy(_._1).foreach { case (name, values) =>
        if (values.size > 1) problems += s"Event '${event.id}' declares attribute '$name' more than once."
      }
      event.occurrence.filter(_ <= 0).foreach { value =>
        problems += s"Event '${event.id}' has non-positive occurrence $value."
      }
      if (event.multiplicity <= 0) problems += s"Event '${event.id}' has non-positive multiplicity ${event.multiplicity}."
      if (event.multiplicity > 1 && event.occurrence.nonEmpty) {
        problems += s"Repeated event '${event.id}' cannot also declare an observed occurrence ordinal."
      }
      event.attributes.foreach { case (attribute, pattern) =>
        if (attribute.isEmpty) problems += s"Event '${event.id}' declares an empty attribute name."
        bindingKeys(pattern).filter(key => !key.name.matches(StableName)).foreach { key =>
          problems += s"Invalid ${key.kind.displayName} binding name '${key.name}' in event '${event.id}'."
        }
        validateValuePattern(pattern).foreach(message => problems += s"Event '${event.id}' $message")
      }
      validateEventPatterns(event).foreach(message => problems += s"Event '${event.id}' $message")
    }

    contract.events.filter(containsCompilerBridgePattern).foreach { event =>
      problems += s"Compiler-bridge event '${event.id}' must belong to one validated optional pair."
    }

    contract.optionalGroups.groupBy(_.name).foreach { case (name, values) =>
      if (values.size > 1) problems += s"Duplicate optional semantic group name '$name'."
    }
    contract.optionalGroups.foreach { group =>
      if (!group.name.matches(StableName)) problems += s"Invalid optional semantic group name '${group.name}'."
      if (group.events.isEmpty) problems += s"Optional semantic group '${group.name}' must declare at least one event."
      group.ownership.foreach { ownership =>
        validateOwnership(ownership).foreach(message => problems += s"Optional semantic group '${group.name}' $message")
      }
      val ids = group.events.map(_.id).toSet
      val allowedIds = requiredPrototypeIds ++ ids
      validateEdges(group.happensBefore, allowedIds, s"Optional semantic group '${group.name}'", problems)
      group.happensBefore.filterNot(edge => ids.contains(edge.before) || ids.contains(edge.after)).foreach { edge =>
        problems += s"Optional semantic group '${group.name}' edge '${edge.before}' -> '${edge.after}' must involve a group event."
      }
      if (group.happensBefore.forall(edge => allowedIds.contains(edge.before) && allowedIds.contains(edge.after))) {
        findCycle(allowedIds, group.happensBefore).foreach { cycle =>
          problems += s"Optional semantic group '${group.name}' happens-before graph contains a cycle: ${cycle.mkString(" -> ")}."
        }
      }
      validateCompilerBridgeGroup(group).foreach(message =>
        problems += s"Optional semantic group '${group.name}' $message"
      )
    }
    validatePlainOutput(contract.plainOutput, problems)

    contract.lifecycles.groupBy(_.name).foreach { case (name, values) =>
      if (values.size > 1) problems += s"Duplicate lifecycle name '$name'."
    }

    var ownedRequiredEvents = contract.events
    val lifecycleEdges = mutable.HashSet.empty[HappensBefore]
    val lifecycleEventIds = mutable.HashSet.empty[SemanticEventId]
    val lifecycleBoundaryKinds = mutable.HashSet.empty[ObservedServiceMessageKind]
    val preparedLifecyclePrototypes = mutable.ArrayBuffer.empty[(SemanticLifecycleRule, Vector[SemanticEventId])]

    contract.lifecycles.foreach { lifecycle =>
      val lifecycleProblems = mutable.ArrayBuffer.empty[String]
      if (!lifecycle.name.matches(StableName)) lifecycleProblems += s"Invalid lifecycle name '${lifecycle.name}'."
      validateOwnership(lifecycle.ownership).foreach(message => lifecycleProblems += s"Lifecycle '${lifecycle.name}' $message")
      if (lifecycle.start == lifecycle.finish) lifecycleProblems += s"Lifecycle '${lifecycle.name}' uses one event as both start and finish."
      if (lifecycle.members.distinct.size != lifecycle.members.size) lifecycleProblems += s"Lifecycle '${lifecycle.name}' contains duplicate members."

      val ids = lifecycle.start +: lifecycle.members :+ lifecycle.finish
      ids.filterNot(requiredPrototypeIds.contains).foreach { id =>
        lifecycleProblems += s"Lifecycle '${lifecycle.name}' references unknown event '$id'."
      }
      val byId = ownedRequiredEvents.map(event => event.id -> event).toMap
      byId.get(lifecycle.start).filter(_.kind != lifecycle.startKind).foreach { _ =>
        lifecycleProblems += s"Lifecycle '${lifecycle.name}' start '${lifecycle.start}' must be ${lifecycle.startKind.wireName}."
      }
      byId.get(lifecycle.finish).filter(_.kind != lifecycle.finishKind).foreach { _ =>
        lifecycleProblems += s"Lifecycle '${lifecycle.name}' finish '${lifecycle.finish}' must be ${lifecycle.finishKind.wireName}."
      }
      Vector(lifecycle.start, lifecycle.finish).flatMap(byId.get).filter(_.multiplicity != 1).foreach { event =>
        lifecycleProblems += s"Lifecycle '${lifecycle.name}' boundary '${event.id}' must have multiplicity 1."
      }

      if (lifecycleProblems.isEmpty) {
        val owned = ids.map { id =>
          val eventIndex = ownedRequiredEvents.indexWhere(_.id == id)
          withOwnership(ownedRequiredEvents(eventIndex), lifecycle.ownership).map(eventIndex -> _)
        }
        owned.collect { case Left(message) => message }.foreach { message =>
          lifecycleProblems += s"Lifecycle '${lifecycle.name}' $message"
        }
        if (lifecycleProblems.isEmpty) {
          owned.collect { case Right((eventIndex, event)) => eventIndex -> event }.foreach { case (eventIndex, event) =>
            ownedRequiredEvents = ownedRequiredEvents.updated(eventIndex, event)
          }
          lifecycleBoundaryKinds ++= Set(lifecycle.startKind, lifecycle.finishKind)
          preparedLifecyclePrototypes += lifecycle -> ids
        }
      }
      problems ++= lifecycleProblems
    }

    validateEdges(contract.happensBefore, requiredPrototypeIds, "Ordering", problems)

    val ownedOptionalGroups = contract.optionalGroups.map { group =>
      val ownedEvents = group.ownership match {
        case None => group.events
        case Some(ownership) => group.events.map { event =>
          withOwnership(event, ownership) match {
            case Right(owned) => owned
            case Left(message) =>
              problems += s"Optional semantic group '${group.name}' $message"
              event
          }
        }
      }
      group.copy(events = ownedEvents)
    }

    val allOwnedPrototypes = ownedRequiredEvents ++ ownedOptionalGroups.flatMap(_.events)
    val expandedByPrototype = allOwnedPrototypes.map { event =>
      event.id -> expandEvent(event)
    }.toMap
    val expandedIds = expandedByPrototype.valuesIterator.flatten.map(_.id).toVector
    expandedIds.groupBy(identity).foreach { case (id, values) =>
      if (values.size > 1) problems += s"Expanded semantic event id '$id' is not unique."
    }

    val expandedEvents = ownedRequiredEvents.flatMap(event => expandedByPrototype(event.id))
    preparedLifecyclePrototypes.foreach { case (lifecycle, ids) =>
      val start = expandedByPrototype(lifecycle.start).head.id
      val members = lifecycle.members.flatMap(id => expandedByPrototype(id).map(_.id))
      val finish = expandedByPrototype(lifecycle.finish).head.id
      lifecycleEventIds ++= start +: members :+ finish
      lifecycleEdges += HappensBefore(start, finish)
      members.foreach { member =>
        lifecycleEdges += HappensBefore(start, member)
        lifecycleEdges += HappensBefore(member, finish)
      }
    }
    val explicitEdges = contract.happensBefore.flatMap(edge => expandEdge(edge, expandedByPrototype))
    val allEdges = explicitEdges ++ lifecycleEdges
    if (problems.isEmpty) {
      findCycle(expandedEvents.map(_.id).toSet, allEdges).foreach { cycle =>
        problems += s"Semantic happens-before graph contains a cycle: ${cycle.mkString(" -> ")}."
      }
    }

    val preparedOptionalGroups = ownedOptionalGroups.map { group =>
      val events = group.events.flatMap(event => expandedByPrototype(event.id))
      val edges = group.happensBefore.flatMap(edge => expandEdge(edge, expandedByPrototype))
      PreparedOptionalSemanticEventGroup(group.name, events, edges, group.events.exists(containsCompilerBridgePattern))
    }
    if (problems.isEmpty) {
      val allExpandedIds = expandedByPrototype.valuesIterator.flatten.map(_.id).toSet
      val allPossibleEdges = allEdges ++ preparedOptionalGroups.flatMap(_.happensBefore)
      findCycle(allExpandedIds, allPossibleEdges).foreach { cycle =>
        problems += s"Semantic graph with all optional groups active contains a cycle: ${cycle.mkString(" -> ")}."
      }
    }

    val capturedKeys = (expandedEvents ++ preparedOptionalGroups.flatMap(_.events)).iterator.flatMap(_.attributes.iterator.flatMap { case (_, pattern) =>
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

    val preparedLifecycles = preparedLifecyclePrototypes.map { case (lifecycle, _) =>
      PreparedSemanticLifecycle(
        lifecycle.name,
        expandedByPrototype(lifecycle.start).head.id,
        lifecycle.members.flatMap(id => expandedByPrototype(id).map(_.id)),
        expandedByPrototype(lifecycle.finish).head.id
      )
    }.toVector
    val lifecycleNames = preparedLifecycles.flatMap { lifecycle =>
      (lifecycle.start +: lifecycle.members :+ lifecycle.finish).map(_ -> lifecycle.name)
    }.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    val requiredCanonical = canonicalPreviousFor(
      ownedRequiredEvents,
      expandedByPrototype,
      allEdges,
      lifecycleNames,
      "required",
      problems
    )
    val optionalCanonical = ownedOptionalGroups.flatMap { group =>
      val edges = preparedOptionalGroups.find(_.name == group.name).map(_.happensBefore).getOrElse(Set.empty)
      canonicalPreviousFor(group.events, expandedByPrototype, edges, Map.empty, group.name, problems)
    }.toMap

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
      lifecycles = preparedLifecycles,
      optionalGroups = preparedOptionalGroups,
      lifecycleEventIds = lifecycleEventIds.toSet,
      lifecycleBoundaryKinds = lifecycleBoundaryKinds.toSet,
      canonicalPrevious = requiredCanonical ++ optionalCanonical,
      distinctBindings = contract.distinctBindings,
      plainOutput = contract.plainOutput,
      processResult = contract.processResult,
      matcherStateBudget = contract.matcherStateBudget
    ))
  }

  private def validateValuePattern(pattern: SemanticValuePattern): Vector[String] = pattern match {
    case SemanticValuePattern.DependencyResource(project, configuration, url, outcomes) =>
      Option.when(project.isEmpty)("declares a dependency resource with an empty project.").toVector ++
        Option.when(configuration.isEmpty)("declares a dependency resource with an empty configuration.").toVector ++
        Option.when(!isExactHttpUrl(url))(s"declares an invalid exact dependency URL '$url'.").toVector ++
        Option.when(outcomes.isEmpty)("declares a dependency resource without an allowed outcome.").toVector
    case SemanticValuePattern.DependencyResolveFailure(
      coordinate,
      repositoryUrl,
      _,
      _,
      maximumInternalFrames
    ) =>
      Option.when(!coordinate.matches("[^:\\s]+:[^:\\s]+:[^:\\s]+"))(
        s"declares an invalid exact dependency coordinate '$coordinate'."
      ).toVector ++
        Option.when(!isExactHttpUrl(repositoryUrl))(
          s"declares an invalid exact dependency repository URL '$repositoryUrl'."
        ).toVector ++
        Option.when(maximumInternalFrames < 0)(
          s"declares a negative dependency-internal-frame bound $maximumInternalFrames."
        ).toVector
    case SemanticValuePattern.DependencyResolutionSummary(local, downloads, failed, reportCache) =>
      Vector(
        "local-cache-hit" -> local,
        "download" -> downloads,
        "failed-download-attempt" -> failed,
        "update-report-cache-hit" -> reportCache
      ).collect {
        case (name, value) if value < 0 => s"declares negative $name count $value."
      }
    case SemanticValuePattern.UserFailure(prefix, frames, framework, maximum, allowedGeneratedOwners) =>
      Option.when(prefix.isEmpty)("declares an empty user-failure prefix.").toVector ++
        Option.when(frames.isEmpty)("declares no exact user stack frames.").toVector ++
        Option.when(frames.exists(frame => frame.isEmpty || frame.contains('\n')))(
          "declares an empty or multiline user stack frame."
        ).toVector ++
        Option.when(frames.distinct.size != frames.size)(
          "declares duplicate exact user stack frames."
        ).toVector ++
        Option.when(maximum < 0)(s"declares a negative framework-frame bound $maximum.").toVector ++
        allowedGeneratedOwners.toVector.sorted.collect {
          case owner if !owner.matches(FullyQualifiedJvmOwner) =>
            s"declares invalid generated ScalaTest owner '$owner'."
        } ++
        Option.when(allowedGeneratedOwners.nonEmpty && framework != RecognizedTestFramework.ScalaTest)(
          "declares generated ScalaTest owners for a non-ScalaTest user failure."
        ).toVector
    case SemanticValuePattern.LinePrefixedThrowableChain(
      linePrefix,
      topException,
      topMessage,
      topFrames,
      causeException,
      causeMessage,
      causeFrames,
      framework,
      maximum
    ) =>
      Option.when(!isNonEmptySingleLine(linePrefix))(
        "declares an empty or multiline throwable-chain line prefix."
      ).toVector ++
        Option.when(!topException.matches(ThrowableClass))(
          s"declares an invalid top throwable class '$topException'."
        ).toVector ++
        Option.when(!isSingleLine(topMessage))(
          "declares a multiline top throwable message."
        ).toVector ++
        validateRequiredThrowableFrames("top", topFrames) ++
        Option.when(!causeException.matches(ThrowableClass))(
          s"declares an invalid cause throwable class '$causeException'."
        ).toVector ++
        Option.when(!isSingleLine(causeMessage))(
          "declares a multiline cause throwable message."
        ).toVector ++
        validateRequiredThrowableFrames("cause", causeFrames) ++
        Option.when(maximum < minimumThrowableInternalFrames(framework))(
          s"declares recognized-frame bound $maximum below the ${minimumThrowableInternalFrames(framework)} " +
            s"internal frames required for ${framework.toString} throwable sections."
        ).toVector
    case _ => Vector.empty
  }

  private def validateRequiredThrowableFrames(section: String, frames: Vector[String]): Vector[String] =
    Option.when(frames.isEmpty)(s"declares no exact $section user stack frames.").toVector ++
      Option.when(frames.exists(frame => !frame.matches(ExactStackFrame)))(
        s"declares an empty, multiline, or malformed $section user stack frame."
      ).toVector ++
      Option.when(frames.distinct.size != frames.size)(
        s"declares duplicate exact $section user stack frames."
      ).toVector

  private def minimumThrowableInternalFrames(framework: RecognizedTestFramework): Int = framework match {
    case RecognizedTestFramework.ScalaTest => 4 // one finite reflection role and one task anchor per section
    case _ => 2 // one finite reflection role per section
  }

  private def isNonEmptySingleLine(value: String): Boolean =
    value.nonEmpty && isSingleLine(value)

  private def isSingleLine(value: String): Boolean =
    !value.exists("\r\n".contains(_))

  private def validateEventPatterns(event: ExpectedSemanticEvent): Vector[String] =
    event.attributes.flatMap { case (attribute, pattern) => pattern match {
      case SemanticValuePattern.DependencyResource(_, _, _, outcomes) =>
        val statuses = outcomes.map(dependencyStatus)
        val expectedStatus = Option.when(statuses.size == 1)(statuses.head)
        Option.when(attribute != "text")("must use DependencyResource only for attribute 'text'.").toVector ++
          Option.when(event.kind != ObservedServiceMessageKind.BuildLogMessage)(
            "must use DependencyResource only on a message event."
          ).toVector ++
          Option.when(statuses.size > 1)(
            "mixes NORMAL and WARNING dependency outcomes; split them into separate expected events."
          ).toVector ++ expectedStatus.toVector.flatMap { status =>
            event.attributes.find(_._1 == "status") match {
              case Some((_, SemanticValuePattern.Exact(actual))) if actual == status => Vector.empty
              case _ => Vector(s"must declare exact status '$status' for its dependency outcome family.")
            }
          }
      case _: SemanticValuePattern.DependencyResolveFailure =>
        Option.when(attribute != "text")(
          "must use DependencyResolveFailure only for attribute 'text'."
        ).toVector ++
          Option.when(event.kind != ObservedServiceMessageKind.BuildLogMessage)(
            "must use DependencyResolveFailure only on a message event."
          ).toVector ++
          (event.attributes.find(_._1 == "status") match {
            case Some((_, SemanticValuePattern.Exact("ERROR"))) => Vector.empty
            case _ => Vector("must declare exact status 'ERROR' for a dependency resolution failure.")
          })
      case _: SemanticValuePattern.DependencyResolutionSummary =>
        Option.when(attribute != "text")(
          "must use DependencyResolutionSummary only for attribute 'text'."
        ).toVector ++
          Option.when(event.kind != ObservedServiceMessageKind.BuildLogMessage)(
            "must use DependencyResolutionSummary only on a message event."
          ).toVector ++
          (event.attributes.find(_._1 == "status") match {
            case Some((_, SemanticValuePattern.Exact("NORMAL"))) => Vector.empty
            case _ => Vector("must declare exact status 'NORMAL' for a dependency resolution summary.")
          })
      case SemanticValuePattern.StructuredSbtDebug(_) =>
        Option.when(attribute != "text")(
          "must use StructuredSbtDebug only for attribute 'text'."
        ).toVector ++
          Option.when(event.kind != ObservedServiceMessageKind.BuildLogMessage)(
            "must use StructuredSbtDebug only on a message event."
          ).toVector ++
          (event.attributes.find(_._1 == "status") match {
            case Some((_, SemanticValuePattern.Exact("NORMAL"))) => Vector.empty
            case _ => Vector("must declare exact status 'NORMAL' for structured SBT/Zinc debug output.")
          })
      case SemanticValuePattern.JdkMethodReflectionFrame(_) =>
        Option.when(attribute != "text")(
          "must use JdkMethodReflectionFrame only for attribute 'text'."
        ).toVector ++
          Option.when(event.kind != ObservedServiceMessageKind.BuildLogMessage)(
            "must use JdkMethodReflectionFrame only on a message event."
          ).toVector ++
          (event.attributes.find(_._1 == "status") match {
            case Some((_, SemanticValuePattern.Exact("ERROR"))) => Vector.empty
            case _ => Vector("must declare exact status 'ERROR' for a JDK reflection task-failure frame.")
          })
      case _: SemanticValuePattern.LinePrefixedThrowableChain =>
        Option.when(attribute != "text")(
          "must use LinePrefixedThrowableChain only for attribute 'text'."
        ).toVector ++
          Option.when(event.kind != ObservedServiceMessageKind.BuildLogMessage)(
            "must use LinePrefixedThrowableChain only on a message event."
          ).toVector
      case _ => Vector.empty
    }}

  private def dependencyStatus(outcome: DependencyResourceOutcome): String = outcome match {
    case DependencyResourceOutcome.LocalCacheHit | DependencyResourceOutcome.Downloaded => "NORMAL"
    case DependencyResourceOutcome.FailedDownloadAttempt => "WARNING"
  }

  private def containsCompilerBridgePattern(event: ExpectedSemanticEvent): Boolean =
    event.attributes.exists { case (_, pattern) => isCompilerBridgePattern(pattern) }

  private def isCompilerBridgePattern(pattern: SemanticValuePattern): Boolean = pattern match {
    case SemanticValuePattern.CompilerBridgeAnnouncement | SemanticValuePattern.CompilerBridgeCompletion => true
    case _ => false
  }

  private def validateCompilerBridgeGroup(group: OptionalSemanticEventGroup): Vector[String] = {
    val bridgeAttributes = group.events.flatMap { event =>
      event.attributes.collect { case (attribute, pattern) if isCompilerBridgePattern(pattern) =>
        (event, attribute, pattern)
      }
    }
    if (bridgeAttributes.isEmpty) return Vector.empty

    val announcements = bridgeAttributes.collect {
      case (event, attribute, SemanticValuePattern.CompilerBridgeAnnouncement) => event -> attribute
    }
    val completions = bridgeAttributes.collect {
      case (event, attribute, SemanticValuePattern.CompilerBridgeCompletion) => event -> attribute
    }
    val expectedEdge = for {
      (announcement, _) <- announcements.headOption
      (completion, _) <- completions.headOption
    } yield HappensBefore(announcement.id, completion.id)
    Option.when(group.events.size != 2 || bridgeAttributes.size != 2 || announcements.size != 1 || completions.size != 1)(
      "must contain exactly one compiler-bridge announcement and one completion event."
    ).toVector ++
      Option.when(bridgeAttributes.exists(_._2 != "text"))(
        "must use compiler-bridge patterns only for attribute 'text'."
      ).toVector ++
      Option.when(group.events.exists(event => event.kind != ObservedServiceMessageKind.BuildLogMessage || event.multiplicity != 1))(
        "requires two multiplicity-1 message events."
      ).toVector ++
      Option.when(group.events.exists { event =>
        !event.attributes.contains("status" -> SemanticValuePattern.Exact("NORMAL"))
      })("requires exact NORMAL status on both events.").toVector ++
      Option.when(announcements.headOption.exists { case (event, _) =>
        group.events.headOption.forall(_.id != event.id)
      } || completions.headOption.exists { case (event, _) =>
        group.events.lastOption.forall(_.id != event.id)
      })(
        "must declare the announcement before the completion."
      ).toVector ++
      Option.when(group.ownership.isEmpty)("requires shared Flow or BuildId ownership.").toVector ++
      Option.when(expectedEdge.exists(edge => !group.happensBefore.contains(edge)))(
        "requires the announcement-to-completion edge."
      ).toVector
  }

  private def validateOwnership(pattern: SemanticValuePattern): Vector[String] = pattern match {
    case SemanticValuePattern.Exact(value) =>
      Option.when(value.isEmpty)("requires a non-empty exact ownership value.").toVector
    case _ => ownershipKey(pattern) match {
      case None => Vector("requires ownership to be Exact, Bound, or Embedded.")
      case Some(key) =>
        Option.when(!key.name.matches(StableName))(
          s"has invalid ${key.kind.displayName} ownership binding name '${key.name}'."
        ).toVector ++ Option.when(!key.kind.isOwnership)(
          s"requires Flow or BuildId ownership, got ${key.kind.displayName} '${key.name}'."
        ).toVector
    }
  }

  private def validateEdges(
    edges: Set[HappensBefore],
    ids: Set[SemanticEventId],
    description: String,
    problems: mutable.ArrayBuffer[String]
  ): Unit = edges.foreach { edge =>
    if (!ids.contains(edge.before)) problems += s"$description edge references unknown event '${edge.before}'."
    if (!ids.contains(edge.after)) problems += s"$description edge references unknown event '${edge.after}'."
    if (edge.before == edge.after) problems += s"$description edge for '${edge.before}' is self-referential."
  }

  private def validatePlainOutput(
    contract: PlainOutputContract,
    problems: mutable.ArrayBuffer[String]
  ): Unit = contract match {
    case PlainOutputContract.Patterns(patterns) =>
      patterns.map(stripPlainPatternPlacement).collect {
        case PlainOutputPattern.CompilerBridgeAnnouncement | PlainOutputPattern.CompilerBridgeCompletion => ()
      }.foreach(_ => problems += "Compiler-bridge plain patterns must belong to one optional announcement/completion group.")
      patterns.foreach(pattern => validatePlainPatternPlacement(pattern, problems))
      val groups = patterns.collect { case group: PlainOutputPattern.OptionalGroup => group }
      groups.groupBy(_.name).foreach { case (name, values) =>
        if (values.size > 1) problems += s"Duplicate optional plain-output group name '$name'."
      }
      groups.foreach { group =>
        if (!group.name.matches(StableName)) problems += s"Invalid optional plain-output group name '${group.name}'."
        if (group.patterns.isEmpty) problems += s"Optional plain-output group '${group.name}' must declare at least one line."
        if (group.patterns.exists(_.isInstanceOf[PlainOutputPattern.OptionalGroup])) {
          problems += s"Optional plain-output group '${group.name}' cannot contain another optional group."
        }
        val bridgePatterns = group.patterns.flatMap(plainPatternLeaves).filter {
          case PlainOutputPattern.CompilerBridgeAnnouncement | PlainOutputPattern.CompilerBridgeCompletion => true
          case _ => false
        }
        val strippedPatterns = group.patterns.map(stripPlainPatternPlacement)
        val bridgePlacementKinds = group.patterns.map(plainPatternPlacement).distinct
        if (bridgePatterns.nonEmpty && (strippedPatterns != Vector(
          PlainOutputPattern.CompilerBridgeAnnouncement,
          PlainOutputPattern.CompilerBridgeCompletion
        ) || bridgePlacementKinds.size != 1)) {
          problems += s"Optional plain-output group '${group.name}' must contain exactly the compiler-bridge announcement followed by completion."
        }
      }
      patterns.flatMap(plainPatternLeaves).foreach {
        case PlainOutputPattern.CompilerCompileInfo(workspace, targetSuffix)
          if !targetSuffix.startsWith("/target/") || targetSuffix.contains("\n") || targetSuffix.contains("\r") =>
          problems += s"Compiler compile-info target suffix must be one non-empty /target/ path, got '$targetSuffix'."
        case PlainOutputPattern.CompilerCompileInfo(workspace, _)
          if workspace.kind != SemanticBindingKind.Path || !workspace.name.matches(StableName) =>
          problems += s"Compiler compile-info requires a valid path binding, got '$workspace'."
        case PlainOutputPattern.CompilerInspectionDiagnostic(workspace, sourceSuffix, _, column)
          if workspace.kind != SemanticBindingKind.Path || !workspace.name.matches(StableName) ||
            !sourceSuffix.startsWith("/") || sourceSuffix.contains("\n") || sourceSuffix.contains("\r") ||
            column <= 0 =>
          problems += s"Compiler inspection diagnostic requires a valid path binding, source suffix, and positive column."
        case PlainOutputPattern.ScalaTestLogbackLine(thread, _, exactSuffix)
          if thread.kind != SemanticBindingKind.LogbackThread || !thread.name.matches(StableName) ||
            !isNonEmptySingleLine(exactSuffix) =>
          problems += "ScalaTest Logback line requires a valid Logback-thread binding and a non-empty single-line exact suffix."
        case _ => ()
      }
    case _ => ()
  }

  private def validatePlainPatternPlacement(
    pattern: PlainOutputPattern,
    problems: mutable.ArrayBuffer[String]
  ): Unit = pattern match {
    case PlainOutputPattern.BeforeFirstTestInSuite(suiteName, invocationOrdinal, child) =>
      if (!isNonEmptySingleLine(suiteName)) {
        problems += "A suite-relative plain-output placement requires a non-empty single-line exact suite name."
      }
      if (invocationOrdinal <= 0) {
        problems += s"A suite-relative plain-output placement requires a positive invocation ordinal, got $invocationOrdinal."
      }
      validatePlainPlacementChild(child, problems)
    case PlainOutputPattern.BeforeServiceMessages(child) => validatePlainPlacementChild(child, problems)
    case PlainOutputPattern.AfterServiceMessages(child) => validatePlainPlacementChild(child, problems)
    case PlainOutputPattern.OptionalGroup(_, children) =>
      children.foreach(child => validatePlainPatternPlacement(child, problems))
    case _ => ()
  }

  private def validatePlainPlacementChild(
    child: PlainOutputPattern,
    problems: mutable.ArrayBuffer[String]
  ): Unit = {
    if (isPlainPlacementWrapper(child)) {
      problems += "Plain-output placement wrappers cannot be nested."
    } else if (child.isInstanceOf[PlainOutputPattern.OptionalGroup]) {
      problems += "A plain-output optional group must own any placement wrappers on its child lines."
    } else {
      validatePlainPatternPlacement(child, problems)
    }
  }

  private def isPlainPlacementWrapper(pattern: PlainOutputPattern): Boolean = pattern match {
    case _: PlainOutputPattern.BeforeServiceMessages | _: PlainOutputPattern.AfterServiceMessages |
         _: PlainOutputPattern.BeforeFirstTestInSuite => true
    case _ => false
  }

  private def plainPatternLeaves(pattern: PlainOutputPattern): Vector[PlainOutputPattern] = pattern match {
    case PlainOutputPattern.BeforeServiceMessages(child) => plainPatternLeaves(child)
    case PlainOutputPattern.AfterServiceMessages(child) => plainPatternLeaves(child)
    case PlainOutputPattern.BeforeFirstTestInSuite(_, _, child) => plainPatternLeaves(child)
    case PlainOutputPattern.OptionalGroup(_, children) => children.flatMap(plainPatternLeaves)
    case leaf => Vector(leaf)
  }

  private def stripPlainPatternPlacement(pattern: PlainOutputPattern): PlainOutputPattern = pattern match {
    case PlainOutputPattern.BeforeServiceMessages(child) => stripPlainPatternPlacement(child)
    case PlainOutputPattern.AfterServiceMessages(child) => stripPlainPatternPlacement(child)
    case PlainOutputPattern.BeforeFirstTestInSuite(_, _, child) => stripPlainPatternPlacement(child)
    case other => other
  }

  private def plainPatternPlacement(pattern: PlainOutputPattern): String = pattern match {
    case PlainOutputPattern.BeforeServiceMessages(_) => "before"
    case PlainOutputPattern.AfterServiceMessages(_) => "after"
    case PlainOutputPattern.BeforeFirstTestInSuite(suiteName, invocationOrdinal, _) =>
      s"suite:$suiteName:$invocationOrdinal"
    case _ => "unplaced"
  }

  private def expandEvent(event: ExpectedSemanticEvent): Vector[ExpectedSemanticEvent] = {
    if (event.multiplicity == 1) Vector(event.copy(multiplicity = 1))
    else (1 to event.multiplicity).toVector.map { ordinal =>
      event.copy(id = SemanticEventId(s"${event.id.value}.$ordinal"), occurrence = None, multiplicity = 1)
    }
  }

  private def expandEdge(
    edge: HappensBefore,
    expandedByPrototype: Map[SemanticEventId, Vector[ExpectedSemanticEvent]]
  ): Set[HappensBefore] = (for {
    before <- expandedByPrototype.getOrElse(edge.before, Vector.empty)
    after <- expandedByPrototype.getOrElse(edge.after, Vector.empty)
  } yield HappensBefore(before.id, after.id)).toSet

  private def canonicalPreviousFor(
    prototypes: Vector[ExpectedSemanticEvent],
    expandedByPrototype: Map[SemanticEventId, Vector[ExpectedSemanticEvent]],
    edges: Set[HappensBefore],
    lifecycleNames: Map[SemanticEventId, Set[String]],
    owner: String,
    problems: mutable.ArrayBuffer[String]
  ): Map[SemanticEventId, SemanticEventId] = {
    val incoming = edges.groupMap(_.after)(_.before).withDefaultValue(Set.empty)
    val outgoing = edges.groupMap(_.before)(_.after).withDefaultValue(Set.empty)
    prototypes.filter(_.multiplicity > 1).flatMap { prototype =>
      val clones = expandedByPrototype(prototype.id)
      val signatures = clones.map { clone =>
        (clone.kind, clone.attributes, incoming(clone.id), outgoing(clone.id), lifecycleNames.getOrElse(clone.id, Set.empty))
      }
      if (signatures.distinct.size != 1) {
        problems += s"Repeated event '${prototype.id}' in '$owner' does not have equivalent ordering/lifecycle structure."
        Vector.empty
      } else clones.sliding(2).collect { case Vector(previous, next) => next.id -> previous.id }.toVector
    }.toMap
  }

  private def bindingKeys(pattern: SemanticValuePattern): Vector[SemanticBindingKey] = pattern match {
    case SemanticValuePattern.Bound(key) => Vector(key)
    case SemanticValuePattern.Embedded(_, key, _) => Vector(key)
    case SemanticValuePattern.DependencyResolveFailure(_, _, ivyHome, _, _) => Vector(ivyHome)
    case _ => Vector.empty
  }

  private def ownershipKey(pattern: SemanticValuePattern): Option[SemanticBindingKey] = pattern match {
    case SemanticValuePattern.Bound(key) => Some(key)
    case SemanticValuePattern.Embedded(_, key, _) => Some(key)
    case _ => None
  }

  private def withOwnership(
    event: ExpectedSemanticEvent,
    ownership: SemanticValuePattern
  ): Either[String, ExpectedSemanticEvent] = event.attributes.find(_._1 == "flowId") match {
    case None => Right(event.copy(attributes = event.attributes :+ ("flowId" -> ownership)))
    case Some((_, existing)) if existing == ownership => Right(event)
    case Some(_) => Left(s"event '${event.id}' must bind attribute 'flowId' to ownership pattern '$ownership'.")
  }

  private def isExactHttpUrl(value: String): Boolean =
    (value.startsWith("http://") || value.startsWith("https://")) && !value.exists(_.isWhitespace)

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
        val nextNodes = outgoing(node).toVector.sortBy(_.value).iterator
        var result = Option.empty[Vector[SemanticEventId]]
        while (nextNodes.hasNext && result.isEmpty) {
          result = visit(nextNodes.next(), path :+ node)
        }
        visiting -= node
        visited += node
        result
      }
    }

    val remaining = nodes.toVector.sortBy(_.value).iterator
    var result = Option.empty[Vector[SemanticEventId]]
    while (remaining.hasNext && result.isEmpty) result = visit(remaining.next(), Vector.empty)
    result
  }
}
