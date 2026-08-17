package jetbrains.buildServer.sbtlogger.utils

import org.jetbrains.sbt.integrationTests.FileUtils
import org.jetbrains.sbt.integrationTests.FileUtils.normalisedAbsolutePath
import org.junit.Assert

import java.io.File
import java.util.regex.{Pattern, PatternSyntaxException}
import scala.collection.mutable

/** Verifies nested-sbt output against named, flow-owning expected-output groups. */
private[sbtlogger] object SbtOutputVerifier {

  def validateExpectationSet(expectations: ExpectationSet, fixtureDirectory: File): Unit =
    compileExpectationSet(expectations, fixtureDirectory)

  def checkOutputFile(outputFile: File, excludesFile: Option[File], expectations: ExpectationSet, fixtureDirectory: File): Unit = {
    val lines = FileUtils.readLines(outputFile)
    lines.foreach(line => println(TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput(line)))
    checkOutputLines(lines, excludesFile, expectations, fixtureDirectory)
  }

  def checkOutputFile(outputFile: File, excludesFile: Option[File], requiredFiles: Seq[File]): Unit =
    checkOutputFile(outputFile, excludesFile, legacyExpectations(requiredFiles), new File("."))

  def checkOutputText(output: String, excludesFile: Option[File], expectations: ExpectationSet, fixtureDirectory: File): Unit =
    checkOutputLines(output.linesIterator.toVector, excludesFile, expectations, fixtureDirectory)

  /** Compatibility overload retained for focused legacy verifier tests. */
  def checkOutputText(output: String, excludesFile: Option[File], requiredFiles: Seq[File]): Unit =
    checkOutputText(output, excludesFile, legacyExpectations(requiredFiles), new File("."))

  def assertCompilationLifecycle(output: String, expectation: SbtCompilationLifecycleExpectation): Unit = {
    val lines = output.linesIterator.toVector
    val lifecycles = lines.zipWithIndex.flatMap { case (line, index) =>
      parseServiceMessage(line).collect {
        case ("compilationStarted", attributes) if attributes.contains("compiler") && attributes.contains("flowId") =>
          CompilationLifecycleEvent(started = true, attributes("compiler"), attributes("flowId"), index)
        case ("compilationFinished", attributes) if attributes.contains("compiler") && attributes.contains("flowId") =>
          CompilationLifecycleEvent(started = false, attributes("compiler"), attributes("flowId"), index)
      }
    }
    val grouped = lifecycles.groupBy(event => (event.compiler, event.flowId))
    expectation match {
      case SbtCompilationLifecycleExpectation.Complete(expectedClosures, _) =>
        Assert.assertEquals(s"Expected $expectedClosures compilation lifecycle closures, found ${grouped.size}: ${grouped.keys.mkString(", ")}", expectedClosures, grouped.size)
        grouped.foreach { case ((compiler, flowId), events) => assertCompleteLifecycle(compiler, flowId, events) }
    }
    val summaries = lines.zipWithIndex.flatMap { case (line, index) =>
      parseServiceMessage(line).collect {
        case ("message", attributes) if attributes.get("status").contains("ERROR") && attributes.get("text").contains("one error found") =>
          LegacyErrorSummary(attributes.get("flowId"), index)
      }
    }
    expectation.expectedLegacyErrorSummaries.foreach { expectedCount =>
      Assert.assertEquals(s"Expected $expectedCount legacy compiler error summaries", expectedCount, summaries.size)
      summaries.foreach(summary => assertLegacyErrorSummaryIsOwned(summary, grouped))
    }
  }

  private def checkOutputLines(lines: Seq[String], excludesFile: Option[File], expectations: ExpectationSet, fixtureDirectory: File): Unit = {
    assertNoForbiddenMatches(findForbiddenMatches(lines, excludesFile.toSeq.flatMap(file => compilePatterns(file, validateFlowPlaceholders = false))))
    val scopes = compileExpectationSet(expectations, fixtureDirectory)
    val candidates = scopes.map { scope =>
      scope.groups.foreach(group => println(s"=== Check group: ${scope.name}/${group.group.name} (${normalisedAbsolutePath(group.file)}) ==="))
      scope -> findScopeMatches(lines, scope)
    }
    candidates.collectFirst { case (_, ScopeSearch.NoCompleteMatch(result, minimumOccurrences)) => result -> minimumOccurrences }
      .foreach { case (result, minimumOccurrences) => assertRequiredGroupMatched(result, minimumOccurrences) }
    val completeScopes = candidates.collect { case (scope, ScopeSearch.Complete(matches)) => scope -> matches }
    if (findCompatibleScopeMatches(completeScopes, Set.empty, Vector.empty).isEmpty) assertNoCompatibleFlowScopeAssignment(completeScopes)
  }

  private def compileExpectationSet(expectations: ExpectationSet, fixtureDirectory: File): Vector[CompiledScope] = {
    validateExpectationShape(expectations)
    val scopes = expectations.scopes.toVector.map { scope =>
      val groups = scope.groups.toVector.map { group =>
        val file = resolveFixtureFile(fixtureDirectory, group.fileName)
        if (!file.isFile) throw new IllegalArgumentException(s"Expected-output group '${group.name}' in scope '${scope.name}' references a missing file: ${normalisedAbsolutePath(file)}")
        val patterns = compilePatterns(file, validateFlowPlaceholders = true)
        if (patterns.isEmpty) throw new IllegalArgumentException(s"Expected-output group '${group.name}' is empty: ${normalisedAbsolutePath(file)}")
        CompiledGroup(scope.name, group, file, patterns)
      }
      CompiledScope(scope.name, groups)
    }
    val duplicates = scopes.flatMap(_.groups).groupBy(_.canonicalText).values.filter(_.size > 1).toVector
    if (duplicates.nonEmpty) {
      val details = duplicates.map(_.map(group => s"${group.scopeName}/${group.group.name}: ${normalisedAbsolutePath(group.file)}").mkString(System.lineSeparator())).mkString(System.lineSeparator() + System.lineSeparator())
      throw new IllegalArgumentException(s"Duplicate expected-output assertion groups were selected:${System.lineSeparator()}$details")
    }
    scopes
  }

  private def validateExpectationShape(expectations: ExpectationSet): Unit = {
    if (expectations.scopes.isEmpty) throw new IllegalArgumentException("An expectation set must contain at least one flow scope.")
    assertDistinctNames("flow-scope", expectations.scopes.map(_.name))
    assertDistinctNames("assertion-group", expectations.scopes.flatMap(_.groups.map(_.name)))
    expectations.scopes.foreach { scope =>
      if (scope.name.trim.isEmpty) throw new IllegalArgumentException("Flow-scope names must not be empty.")
      if (scope.groups.isEmpty) throw new IllegalArgumentException(s"Flow scope '${scope.name}' must contain at least one assertion group.")
      scope.groups.foreach { group =>
        if (group.name.trim.isEmpty) throw new IllegalArgumentException(s"Flow scope '${scope.name}' has an assertion group with an empty name.")
        if (group.fileName.trim.isEmpty) throw new IllegalArgumentException(s"Assertion group '${group.name}' has an empty fixture file name.")
        if (group.minimumOccurrences < 1) throw new IllegalArgumentException(s"Assertion group '${group.name}' must have minimumOccurrences >= 1.")
      }
    }
  }

  private def assertDistinctNames(kind: String, names: Seq[String]): Unit = {
    val duplicates = names.groupBy(identity).collect { case (name, all) if all.size > 1 => name }.toSeq.sorted
    if (duplicates.nonEmpty) throw new IllegalArgumentException(s"Duplicate $kind names: ${duplicates.mkString(", ")}")
  }

  private def resolveFixtureFile(fixtureDirectory: File, fileName: String): File = {
    val file = new File(fileName)
    if (file.isAbsolute) file else new File(fixtureDirectory, fileName)
  }

  private def legacyExpectations(requiredFiles: Seq[File]): ExpectationSet =
    ExpectationSet(Seq(FlowScope("legacy", requiredFiles.zipWithIndex.map { case (file, index) =>
      AssertionGroup(s"legacy-${index + 1}", file.getAbsolutePath)
    })))

  private def findScopeMatches(lines: Seq[String], scope: CompiledScope): ScopeSearch = {
    val groupMatches = scope.groups.map(group => group -> findGroupMatches(lines, group))
    val result = groupMatches.collectFirst { case (group, matches) if matches.isEmpty =>
      ScopeSearch.NoCompleteMatch(matchRequiredPatterns(lines, group), group.group.minimumOccurrences)
    }.getOrElse {
      def combine(index: Int, selected: Vector[GroupMatch]): Vector[ScopeMatch] =
        if (index == groupMatches.size) Vector(ScopeMatch(scope.name, selected))
        else groupMatches(index)._2.flatMap(groupMatch => combine(index + 1, selected :+ groupMatch))
      ScopeSearch.Complete(combine(0, Vector.empty).distinctBy(_.flowIds))
    }
    result
  }

  private def findGroupMatches(lines: Seq[String], group: CompiledGroup): Vector[GroupMatch] = {
    def search(remaining: Int, usedLines: Set[Int], occurrences: Vector[RequiredMatchResult]): Vector[GroupMatch] =
      if (remaining == 0) Vector(GroupMatch(group, occurrences))
      else findCompleteMatches(lines, group, usedLines).flatMap { occurrence =>
        search(remaining - 1, usedLines ++ occurrence.matches.map(_.outputLineNumber - 1), occurrences :+ occurrence)
      }
    search(group.group.minimumOccurrences, Set.empty, Vector.empty).distinctBy { matchResult =>
      matchResult.occurrences.map(result => result.matches.map(_.outputLineNumber) -> result.flowIdBindings)
    }
  }

  private def findCompleteMatches(lines: Seq[String], group: CompiledGroup, unavailableLines: Set[Int]): Vector[RequiredMatchResult] = {
    def search(patternIndex: Int, nextLine: Int, bindings: Map[String, String], matches: Vector[OutputMatch]): Vector[RequiredMatchResult] = {
      if (patternIndex == group.patterns.size) Vector(RequiredMatchResult(group, lines.size, RequiredMatchBranch(matches, bindings, None)))
      else {
        val pattern = group.patterns(patternIndex)
        val candidates = (nextLine until lines.size).iterator.flatMap { lineIndex =>
          if (unavailableLines.contains(lineIndex)) None
          else pattern.matchLine(lines(lineIndex), bindings) match {
            case RequiredPatternMatch.Matched(updated) => Some(RequiredMatchCandidate(OutputMatch(pattern, lineIndex + 1, lines(lineIndex)), updated))
            case _ => None
          }
        }.toVector
        val choices = if (pattern.hasUnboundFlowId(bindings)) firstCandidateForEachFlowId(pattern, candidates) else candidates.headOption.toSeq
        choices.toVector.flatMap(candidate => search(patternIndex + 1, candidate.outputMatch.outputLineNumber, candidate.bindings, matches :+ candidate.outputMatch))
      }
    }
    search(0, 0, Map.empty, Vector.empty)
  }

  private def findCompatibleScopeMatches(scopes: Seq[(CompiledScope, Vector[ScopeMatch])], owned: Set[String], selected: Vector[ScopeMatch]): Option[Vector[ScopeMatch]] =
    if (scopes.isEmpty) Some(selected)
    else scopes.head._2.iterator
      .filter(candidate => candidate.flowIds.intersect(owned).isEmpty)
      .map(candidate => findCompatibleScopeMatches(scopes.tail, owned ++ candidate.flowIds, selected :+ candidate))
      .collectFirst { case Some(matches) => matches }

  private def assertNoCompatibleFlowScopeAssignment(scopes: Seq[(CompiledScope, Vector[ScopeMatch])]): Nothing = {
    val details = scopes.map { case (scope, matches) =>
      s"${scope.name}: ${matches.map(_.flowIds.toSeq.sorted.mkString("{", ", ", "}")).distinct.mkString(", ")}" // candidates
    }.mkString(System.lineSeparator())
    val message = s"""Output verification failed: no globally compatible flow-scope assignment exists.
                     |Concrete flow IDs may be owned by only one scope.
                     |Candidate flow IDs by scope:
                     |$details
                     |""".stripMargin
    println(message)
    Assert.fail(message)
    throw new AssertionError("unreachable")
  }

  private def findForbiddenMatches(lines: Seq[String], patterns: Seq[ExpectedPattern]): Seq[ForbiddenMatch] =
    for { line <- lines; pattern <- patterns if pattern.matches(line) } yield ForbiddenMatch(pattern, line)

  private def matchRequiredPatterns(lines: Seq[String], group: CompiledGroup): RequiredMatchResult = {
    def search(patternIndex: Int, nextLine: Int, bindings: Map[String, String]): RequiredMatchBranch = {
      if (patternIndex == group.patterns.size) RequiredMatchBranch(Vector.empty, bindings, None)
      else {
        val pattern = group.patterns(patternIndex)
        val candidates = (nextLine until lines.size).iterator.map { lineIndex =>
          pattern.matchLine(lines(lineIndex), bindings) match {
            case RequiredPatternMatch.Matched(updated) => Some(RequiredMatchCandidate(OutputMatch(pattern, lineIndex + 1, lines(lineIndex)), updated))
            case conflict: RequiredPatternMatch.ConflictingFlowId => Some(conflict)
            case RequiredPatternMatch.NotMatched => None
          }
        }.flatten.toVector
        val successful = candidates.collect { case candidate: RequiredMatchCandidate => candidate }
        val conflicts = candidates.collect { case conflict: RequiredPatternMatch.ConflictingFlowId => conflict }
        val choices = if (pattern.hasUnboundFlowId(bindings)) firstCandidateForEachFlowId(pattern, successful) else successful.headOption.toSeq
        choices.foldLeft(RequiredMatchBranch(Vector.empty, bindings, conflicts.headOption)) { (best, candidate) =>
          val continuation = search(patternIndex + 1, candidate.outputMatch.outputLineNumber, candidate.bindings)
          RequiredMatchBranch.best(best, continuation.prepend(candidate.outputMatch).withFallbackConflict(conflicts.headOption))
        }
      }
    }
    RequiredMatchResult(group, lines.size, search(0, 0, Map.empty))
  }

  private def firstCandidateForEachFlowId(pattern: ExpectedPattern, candidates: Seq[RequiredMatchCandidate]): Seq[RequiredMatchCandidate] = {
    val seen = mutable.Set.empty[String]
    candidates.filter(candidate => seen.add(candidate.bindings(pattern.flowIdPlaceholder.get)))
  }

  private def assertNoForbiddenMatches(matches: Seq[ForbiddenMatch]): Unit = if (matches.nonEmpty) {
    val message = s"""===================== ERROR ==========================
                     |The following lines were found but should not be there:
                     |${matches.map(_.diagnosticText).mkString(System.lineSeparator())}
                     |""".stripMargin
    println(message)
    Assert.fail(message)
  }

  private def assertRequiredPatternsMatched(result: RequiredMatchResult): Unit = if (!result.isComplete) {
    val message = s"""Output verification failed: required patterns from ${normalisedAbsolutePath(result.group.file)} were not matched in order.
                     |Matched ${result.matchedCount}/${result.group.patterns.size} patterns across ${result.outputLineCount} captured output lines.
                     |Last matched pattern:
                     |${result.lastMatch.map(_.diagnosticText).getOrElse("<none>")}
                     |First missing pattern:
                     |${result.firstMissingPattern.map(_.diagnosticText).getOrElse("<none>")}
                     |Bound flow-ID placeholders:
                     |${result.flowIdBindings.map { case (token, flowId) => s"$token = '$flowId'" }.mkString(System.lineSeparator()) match {
                          case "" => "<none>"
                          case bindings => bindings
                        }}
                     |Conflicting concrete flow ID:
                     |${result.flowIdConflict.map(_.diagnosticText).getOrElse("<none>")}
                     |See the build log for the complete nested-sbt output.
                     |""".stripMargin
    println(message)
    Assert.fail(message)
  }

  private def assertRequiredGroupMatched(result: RequiredMatchResult, minimumOccurrences: Int): Unit = {
    if (!result.isComplete) assertRequiredPatternsMatched(result)
    else if (minimumOccurrences > 1) {
      val message = s"""Output verification failed: assertion group '${result.group.group.name}' from ${normalisedAbsolutePath(result.group.file)} requires $minimumOccurrences distinct occurrences, but only one complete occurrence was found.
                       |Occurrences must use distinct output-line indexes and independent flow-ID bindings.
                       |See the build log for the complete nested-sbt output.
                       |""".stripMargin
      println(message)
      Assert.fail(message)
    }
  }

  private def compilePatterns(file: File, validateFlowPlaceholders: Boolean): Vector[ExpectedPattern] =
    FileUtils.readLines(file).toVector.zipWithIndex.map { case (line, index) =>
      val lineNumber = index + 1
      if (validateFlowPlaceholders) validateFlowPlaceholder(file, lineNumber, line)
      try {
        val placeholder = FlowIdPlaceholderPattern.findFirstMatchIn(line).map(_.group(1))
        val unbound = placeholder.map(token => Pattern.compile(line.replace(s"flowId='<$token>'", "flowId='[^']*'")))
        ExpectedPattern(file, lineNumber, line, Pattern.compile(line), placeholder, unbound)
      } catch {
        case error: PatternSyntaxException => throw new IllegalArgumentException(s"Invalid regex pattern in ${normalisedAbsolutePath(file)}:$lineNumber: $line", error)
      }
    }

  private def validateFlowPlaceholder(file: File, lineNumber: Int, line: String): Unit = {
    val valid = FlowIdPlaceholderPattern.findAllMatchIn(line).toVector
    val malformed = FlowIdPlaceholderCandidatePattern.findAllIn(line).exists(token => !FlowIdPlaceholderPattern.pattern.matcher(s"flowId='$token'").find())
    if (malformed || valid.size > 1 || (line.contains("flowId='<") && valid.isEmpty)) {
      throw new IllegalArgumentException(s"Malformed flow-ID placeholder in ${normalisedAbsolutePath(file)}:$lineNumber: $line")
    }
  }

  private def parseServiceMessage(line: String): Option[(String, Map[String, String])] =
    ServiceMessagePattern.findFirstMatchIn(line).map { message =>
      message.group(1) -> AttributePattern.findAllMatchIn(Option(message.group(2)).getOrElse("")).map(attribute => attribute.group(1) -> attribute.group(2)).toMap
    }

  private def assertCompleteLifecycle(compiler: String, flowId: String, events: Seq[CompilationLifecycleEvent]): Unit = {
    val starts = events.filter(_.started)
    val finishes = events.filterNot(_.started)
    val label = s"compiler='$compiler', flowId='$flowId'"
    Assert.assertEquals(s"Expected exactly one compilation start for $label", 1, starts.size)
    Assert.assertEquals(s"Expected exactly one compilation finish for $label", 1, finishes.size)
    Assert.assertTrue(s"Compilation finish must follow its start for $label", starts.head.lineIndex < finishes.head.lineIndex)
  }

  private def assertLegacyErrorSummaryIsOwned(summary: LegacyErrorSummary, grouped: Map[(String, String), Seq[CompilationLifecycleEvent]]): Unit = {
    val flowId = summary.flowId.getOrElse("<missing>")
    val owners = grouped.collect {
      case ((compiler, lifecycleFlowId), events) if lifecycleFlowId == flowId =>
        val starts = events.filter(_.started)
        val finishes = events.filterNot(_.started)
        Option.when(starts.size == 1 && finishes.size == 1 && starts.head.lineIndex < summary.lineIndex && summary.lineIndex < finishes.head.lineIndex)(compiler)
    }.flatten
    Assert.assertEquals(s"Expected legacy compiler error summary at line ${summary.lineIndex + 1} on flowId='$flowId' to be inside exactly one complete compilation lifecycle, but found ${owners.size}: ${owners.mkString(", ")}", 1, owners.size)
  }

  private val ServiceMessagePattern = """##teamcity\[([^ ]+)(?: (.*))?\]""".r
  private val AttributePattern = """([^ =]+)='([^']*)'""".r
  private val FlowIdPlaceholderPattern = """flowId='<(flowId[1-9][0-9]*)>'""".r
  private val FlowIdPlaceholderCandidatePattern = """<flowId[^>]*>""".r

  private final case class CompiledScope(name: String, groups: Vector[CompiledGroup])
  private final case class CompiledGroup(scopeName: String, group: AssertionGroup, file: File, patterns: Vector[ExpectedPattern]) {
    def canonicalText: String = {
      val names = mutable.LinkedHashMap.empty[String, String]
      var next = 1
      patterns.map { pattern =>
        FlowIdPlaceholderPattern.replaceAllIn(pattern.text, matched => {
          val normalized = names.getOrElseUpdate(matched.group(1), { val value = s"flowId$next"; next += 1; value })
          s"flowId='<$normalized>'"
        })
      }.mkString("\n")
    }
  }
  private final case class ExpectedPattern(file: File, lineNumber: Int, text: String, pattern: Pattern, flowIdPlaceholder: Option[String], unboundFlowIdPattern: Option[Pattern]) {
    private val boundPatterns = mutable.Map.empty[String, Pattern]
    def matches(line: String): Boolean = pattern.matcher(line).find()
    def hasUnboundFlowId(bindings: Map[String, String]): Boolean = flowIdPlaceholder.exists(token => !bindings.contains(token))
    def matchLine(line: String, bindings: Map[String, String]): RequiredPatternMatch = flowIdPlaceholder match {
      case None if matches(line) => RequiredPatternMatch.Matched(bindings)
      case None => RequiredPatternMatch.NotMatched
      case Some(token) => bindings.get(token) match {
        case Some(flowId) if boundFlowIdPattern(flowId).matcher(line).find() => RequiredPatternMatch.Matched(bindings)
        case Some(_) => RequiredPatternMatch.NotMatched
        case None if unboundFlowIdPattern.get.matcher(line).find() => parseServiceMessage(line).flatMap(_._2.get("flowId")) match {
          case Some(flowId) => bindings.collectFirst { case (boundToken, boundFlowId) if boundFlowId == flowId => boundToken } match {
            case Some(boundToken) => RequiredPatternMatch.ConflictingFlowId(token, flowId, boundToken)
            case None => RequiredPatternMatch.Matched(bindings.updated(token, flowId))
          }
          case None => RequiredPatternMatch.NotMatched
        }
        case None => RequiredPatternMatch.NotMatched
      }
    }
    private def boundFlowIdPattern(flowId: String): Pattern = boundPatterns.getOrElseUpdate(flowId, Pattern.compile(text.replace(s"flowId='<${flowIdPlaceholder.get}>'", s"flowId='${Pattern.quote(flowId)}'")))
    def diagnosticText: String = s"${normalisedAbsolutePath(file)}:$lineNumber: $text"
  }
  private final case class CompilationLifecycleEvent(started: Boolean, compiler: String, flowId: String, lineIndex: Int)
  private final case class LegacyErrorSummary(flowId: Option[String], lineIndex: Int)
  private final case class ForbiddenMatch(pattern: ExpectedPattern, outputLine: String) { def diagnosticText: String = s"${pattern.diagnosticText}${System.lineSeparator()}  matched output: $outputLine" }
  private final case class OutputMatch(pattern: ExpectedPattern, outputLineNumber: Int, outputLine: String) { def diagnosticText: String = s"${pattern.diagnosticText}${System.lineSeparator()}  matched output line $outputLineNumber: $outputLine" }
  private sealed trait RequiredPatternMatch
  private object RequiredPatternMatch {
    final case class Matched(bindings: Map[String, String]) extends RequiredPatternMatch
    final case class ConflictingFlowId(token: String, concreteFlowId: String, alreadyBoundTo: String) extends RequiredPatternMatch { def diagnosticText: String = s"$token cannot bind to '$concreteFlowId': it is already bound to $alreadyBoundTo" }
    case object NotMatched extends RequiredPatternMatch
  }
  private final case class RequiredMatchCandidate(outputMatch: OutputMatch, bindings: Map[String, String])
  private final case class RequiredMatchBranch(matches: Vector[OutputMatch], bindings: Map[String, String], flowIdConflict: Option[RequiredPatternMatch.ConflictingFlowId]) {
    def prepend(outputMatch: OutputMatch): RequiredMatchBranch = copy(matches = outputMatch +: matches)
    def withFallbackConflict(conflict: Option[RequiredPatternMatch.ConflictingFlowId]): RequiredMatchBranch = copy(flowIdConflict = flowIdConflict.orElse(conflict))
  }
  private object RequiredMatchBranch {
    def best(first: RequiredMatchBranch, second: RequiredMatchBranch): RequiredMatchBranch = if (second.matches.size > first.matches.size || (second.matches.size == first.matches.size && second.flowIdConflict.nonEmpty && first.flowIdConflict.isEmpty)) second else first
  }
  private final case class RequiredMatchResult(group: CompiledGroup, outputLineCount: Int, branch: RequiredMatchBranch) {
    def matches: Vector[OutputMatch] = branch.matches
    def matchedCount: Int = matches.size
    def isComplete: Boolean = matchedCount == group.patterns.size
    def lastMatch: Option[OutputMatch] = matches.lastOption
    def flowIdBindings: Map[String, String] = branch.bindings
    def flowIdConflict: Option[RequiredPatternMatch.ConflictingFlowId] = branch.flowIdConflict
    def firstMissingPattern: Option[ExpectedPattern] = group.patterns.lift(matchedCount)
  }
  private final case class GroupMatch(group: CompiledGroup, occurrences: Vector[RequiredMatchResult]) { def flowIds: Set[String] = occurrences.flatMap(_.flowIdBindings.values).toSet }
  private final case class ScopeMatch(scopeName: String, groups: Vector[GroupMatch]) { def flowIds: Set[String] = groups.flatMap(_.flowIds).toSet }
  private sealed trait ScopeSearch
  private object ScopeSearch {
    final case class Complete(matches: Vector[ScopeMatch]) extends ScopeSearch
    final case class NoCompleteMatch(result: RequiredMatchResult, minimumOccurrences: Int) extends ScopeSearch
  }
}
