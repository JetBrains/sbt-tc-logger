package jetbrains.buildServer.sbtlogger.utils
import org.jetbrains.sbt.integrationTests.FileUtils
import org.jetbrains.sbt.integrationTests.FileUtils.normalisedAbsolutePath
import org.junit.Assert

import java.io.File
import java.util.regex.{Pattern, PatternSyntaxException}
import scala.collection.mutable

/**
 * Verifies nested sbt console output against fixture regex files.
 *
 * Required patterns must appear in order, while exclude patterns fail the test if they appear anywhere in the output.
 */
private[sbtlogger] object SbtOutputVerifier {

  /**
   * Checks output captured in a file against regex fixtures.
   *
   * The output file is echoed with TeamCity service messages made inert, then checked in the same way as [[checkOutputText]].
   * This is mainly useful for ad-hoc agent logs; for example, such a log can be checked against
   * `test/testdata/1.0/compilation/multiProject/output.txt`.
   */
  def checkOutputFile(outputFile: File, excludesFile: Option[File], requiredFiles: Seq[File]): Unit = {
    val outputLines = FileUtils.readLines(outputFile)
    echoOutputForDiagnostics(outputLines)
    checkOutputLines(outputLines, excludesFile, requiredFiles)
  }

  /**
   * Checks captured nested-sbt output against optional forbidden regexes and one or more required regex files.
   *
   * Each line in `excludesFile`, when present, is compiled as a regex. \
   * If any forbidden regex is found in any output line, the assertion fails. \
   * Each line in every `requiredFiles` entry is also compiled as a regex, but required patterns must be found in order. \
   * Unrelated output lines may appear between required matches. Every required file is checked independently against the
   * complete output: it has its own ordered subsequence and its own placeholder bindings, and does not consume lines or
   * bindings from another required file.
   *
   * Required fixtures may replace a TeamCity `flowId` value with `flowId='<flowIdN>'`, where `N` is a positive integer.
   * The first matching service message binds that token to its concrete flow ID. Later occurrences of the same token in
   * the *same fixture file* must match that value; two different tokens must bind to two different concrete values.
   * All other fixture text remains an ordinary Java regex. Exclude fixtures intentionally have no placeholder semantics
   * and continue to be unrestricted regex assertions.
   *
   * Real required-pattern fixtures include `test/testdata/1.0/compilation/failure/output.txt`,
   * `test/testdata/1.0/testSupport/JUnit_PassAndFailure/output.txt`, and `test/testdata/1.0/compilation/warnings/output.txt`.
   * A real forbidden-pattern fixture is `test/testdata/1.0/compilerLogLevel/error/excludes.txt`.
   *
   * Example:
   * ```txt
   * output:
   *   "[info] compiling"
   *   "[success] done"
   *
   * required file:
   *   "\\[info\\] compiling"
   *   "\\[success\\]"
   *
   * result: passes
   * ```
   *
   * Example:
   * ```txt
   * output:
   *   "[success] done"
   *   "[info] compiling"
   *
   * required file:
   *   "\\[info\\] compiling"
   *   "\\[success\\]"
   *
   * result: fails, because the required patterns appear in the wrong order
   * ```
   */
  def checkOutputText(
    output: String,
    excludesFile: Option[File],
    requiredFiles: Seq[File]
  ): Unit = {
    checkOutputLines(output.linesIterator.toVector, excludesFile, requiredFiles)
  }

  /**
   * Verifies complete, non-duplicated compiler lifecycles for focused regression fixtures.
   *
   * Unlike the regex fixture matcher, this keeps the actual TeamCity `compiler` and `flowId` attributes together.
   * It therefore rejects an unmatched or duplicate close even when a permissive `flowId='.*'` regex would match it.
   * Legacy `one error found` messages are emitted on the reporter's flow rather than the compiler lifecycle flow, so this
   * assertion counts those summaries but deliberately does not associate their flow IDs with a compiler pair.
   */
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

    Assert.assertEquals(
      s"Expected ${expectation.expectedClosures} compilation lifecycle closures, found ${grouped.size}: ${grouped.keys.mkString(", ")}",
      expectation.expectedClosures,
      grouped.size
    )

    grouped.foreach { case ((compiler, flowId), events) =>
      val starts = events.filter(_.started)
      val finishes = events.filterNot(_.started)
      val label = s"compiler='$compiler', flowId='$flowId'"
      Assert.assertEquals(s"Expected exactly one compilation start for $label", 1, starts.size)
      Assert.assertEquals(s"Expected exactly one compilation finish for $label", 1, finishes.size)
      val start = starts.head
      val finish = finishes.head
      Assert.assertTrue(s"Compilation finish must follow its start for $label", start.lineIndex < finish.lineIndex)

    }

    val legacyErrorSummaryCount = lines.count { line =>
      parseServiceMessage(line).exists { case (name, attributes) =>
        name == "message" &&
          attributes.get("status").contains("ERROR") &&
          attributes.get("flowId").isDefined &&
          attributes.get("text").contains("one error found")
      }
    }
    expectation.expectedLegacyErrorSummaries.foreach { expectedCount =>
      Assert.assertEquals(s"Expected $expectedCount legacy compiler error summaries", expectedCount, legacyErrorSummaryCount)
    }
  }

  /**
   * Applies the verifier contract to already-split output lines.
   *
   * Excludes are checked first so forbidden output is reported even when no required file is supplied. Required files are
   * checked independently: each file describes one acceptable ordered subsequence that must be present in the same full
   * output. Consequently, placeholders are deliberately scoped to one required file rather than the whole test case.
   *
   * Some scenarios pass multiple required files for the same output stream; for example,
   * `test/testdata/1.0/testSupport/ScalaTest_PassAndFailure/output.txt` and
   * `test/testdata/1.0/testSupport/ScalaTest_PassAndFailure/output1.txt`.
   */
  private def checkOutputLines(allLines: Seq[String], excludesFile: Option[File], requiredFiles: Seq[File]): Unit = {
    val excludedPatterns = excludesFile.toSeq.flatMap(compilePatterns)

    assertNoForbiddenMatches(findForbiddenMatches(allLines, excludedPatterns))

    requiredFiles.foreach { requiredFile =>
      println(s"=== Check file: ${normalisedAbsolutePath(requiredFile)} ===")
      val requiredPatterns = ExpectedPatternGroup(requiredFile, compilePatterns(requiredFile))
      assertRequiredPatternsMatched(matchRequiredPatterns(allLines, requiredPatterns))
    }
  }

  private def parseServiceMessage(line: String): Option[(String, Map[String, String])] =
    // Nested SBT can append ordinary console text after a service message on the same physical output line. Required
    // fixture regexes have always used `Matcher.find`, so parse the first embedded message with the same tolerance.
    ServiceMessagePattern.findFirstMatchIn(line).map { serviceMessage =>
      val name = serviceMessage.group(1)
      val rawAttributes = Option(serviceMessage.group(2)).getOrElse("")
      name -> AttributePattern.findAllMatchIn(rawAttributes).map { attribute =>
        attribute.group(1) -> attribute.group(2)
      }.toMap
    }

  /**
   * Finds every output line matched by every forbidden pattern.
   *
   * The regex is applied with `find`, not full-line matching.
   * See `test/testdata/1.0/compilerLogLevel/error/excludes.txt`, which forbids the nested sbt output from reporting
   * `Initial source changes` as a normal TeamCity message.
   *
   * Example:
   * ```txt
   * output lines:
   *   "download started"
   *   "[error] forbidden repository"
   *
   * forbidden pattern:
   *   "forbidden"
   *
   * result:
   *   one ForbiddenMatch for "[error] forbidden repository"
   * ```
   */
  private def findForbiddenMatches(allLines: Seq[String], excludedPatterns: Seq[ExpectedPattern]): Seq[ForbiddenMatch] =
    for {
      line <- allLines
      pattern <- excludedPatterns
      if pattern.matches(line)
    } yield ForbiddenMatch(pattern, line)

  /**
   * Finds a valid ordered subsequence of the output for all required patterns, or the best partial subsequence when no
   * complete one exists.
   *
   * Matching advances by at most one required pattern per output line. This preserves the historical fixture semantics:
   * one line cannot satisfy two consecutive expected lines, even if it contains text that would match both regexes.
   *
   * The historical matcher greedily selected the first matching output line. That is sufficient for fixed regexes:
   * choosing an earlier matching line can never prevent a later fixed regex from matching. A previously unbound flow-ID
   * placeholder is different, because an early candidate can bind it to a value incompatible with a later line. For
   * those candidates this method explores each distinct concrete flow ID, while retaining the earliest occurrence of
   * each value. It therefore finds any valid ordered subsequence without the combinatorial cost of retrying equivalent
   * occurrences of the same flow.
   *
   * Ordered multi-line required patterns are used throughout the fixture outputs. Examples include
   * `test/testdata/1.0/compilation/failure/output.txt`, where compilation start, error, finish, and final failure messages
   * must appear in that order, and `test/testdata/1.0/testSupport/JUnit_PassAndFailure/output.txt`, where suite and test messages
   * must appear in their emitted order.
   *
   * Example:
   * ```txt
   * output lines:
   *   "first"
   *   "noise"
   *   "second"
   *
   * required patterns:
   *   "first"
   *   "second"
   *
   * result:
   *   RequiredMatchResult(matchedCount = 2), complete
   * ```
   *
   * Example:
   * ```txt
   * output lines:
   *   "first second"
   *
   * required patterns:
   *   "first"
   *   "second"
   *
   * result:
   *   RequiredMatchResult(matchedCount = 1), incomplete
   * ```
   */
  private def matchRequiredPatterns(
    allLines: Seq[String],
    requiredPatterns: ExpectedPatternGroup
  ): RequiredMatchResult = {
    def search(
      patternIndex: Int,
      nextOutputLineIndex: Int,
      bindings: Map[String, String]
    ): RequiredMatchBranch = {
      if (patternIndex == requiredPatterns.patterns.size) {
        RequiredMatchBranch(Vector.empty, bindings, None)
      } else {
        val currentPattern = requiredPatterns.patterns(patternIndex)
        val candidates = (nextOutputLineIndex until allLines.size).iterator.map { outputLineIndex =>
          currentPattern.matchLine(allLines(outputLineIndex), bindings) match {
            case RequiredPatternMatch.Matched(updatedBindings) =>
              Some(RequiredMatchCandidate(OutputMatch(currentPattern, outputLineIndex + 1, allLines(outputLineIndex)), updatedBindings))
            case conflict: RequiredPatternMatch.ConflictingFlowId =>
              Some(conflict)
            case RequiredPatternMatch.NotMatched =>
              None
          }
        }.flatten.toVector

        val successfulCandidates = candidates.collect { case candidate: RequiredMatchCandidate => candidate }
        val conflicts = candidates.collect { case conflict: RequiredPatternMatch.ConflictingFlowId => conflict }
        val candidateChoices =
          if (currentPattern.hasUnboundFlowId(bindings)) firstCandidateForEachFlowId(currentPattern, successfulCandidates)
          else successfulCandidates.headOption.toSeq

        candidateChoices.foldLeft(RequiredMatchBranch(Vector.empty, bindings, conflicts.headOption)) { (best, candidate) =>
          val continuation = search(patternIndex + 1, candidate.outputMatch.outputLineNumber, candidate.bindings)
          val branch = continuation.prepend(candidate.outputMatch).withFallbackConflict(conflicts.headOption)
          RequiredMatchBranch.best(best, branch)
        }
      }
    }

    val bestBranch = search(patternIndex = 0, nextOutputLineIndex = 0, bindings = Map.empty)
    RequiredMatchResult(requiredPatterns, allLines.size, bestBranch)
  }

  /**
   * Keeps the earliest candidate for each newly bound concrete flow ID.
   *
   * Once a token has chosen a concrete value, a later occurrence of that same value cannot enable any ordered suffix
   * that the earlier occurrence could not also enable. Keeping only the first occurrence makes the placeholder search
   * deterministic and avoids revisiting equivalent subsequences.
   */
  private def firstCandidateForEachFlowId(
    pattern: ExpectedPattern,
    candidates: Seq[RequiredMatchCandidate]
  ): Seq[RequiredMatchCandidate] = {
    val seenFlowIds = mutable.Set.empty[String]
    candidates.filter { candidate =>
      val concreteFlowId = candidate.bindings(pattern.flowIdPlaceholder.get)
      seenFlowIds.add(concreteFlowId)
    }
  }

  private def assertNoForbiddenMatches(matches: Seq[ForbiddenMatch]): Unit = {
    if (matches.nonEmpty) {
      val message =
        s"""===================== ERROR ==========================
           |The following lines were found but should not be there:
           |${matches.map(_.diagnosticText).mkString(System.lineSeparator())}
           |""".stripMargin
      println(message)
      Assert.fail(message)
    }
  }

  private def assertRequiredPatternsMatched(result: RequiredMatchResult): Unit = {
    if (!result.isComplete) {
      val message =
        s"""Output verification failed: required patterns from ${normalisedAbsolutePath(result.patterns.file)} were not matched in order.
           |Matched ${result.matchedCount}/${result.patterns.patterns.size} patterns across ${result.outputLineCount} captured output lines.
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
  }

  /**
   * Compiles a fixture file where each line is one regex pattern and reports invalid regexes with file and line number.
   * The same compiler is used for required files such as `test/testdata/1.0/compilation/warnings/output.txt` and for
   * excludes files such as `test/testdata/1.0/compilerLogLevel/error/excludes.txt`.
   *
   * Example:
   * ```txt
   * fixture line:
   *   "\\[success\\] Total time: .*"
   *
   * output line:
   *   "[success] Total time: 2 s"
   *
   * result:
   *   ExpectedPattern.matches(output line) == true
   * ```
   */
  private def compilePatterns(file: File): Seq[ExpectedPattern] =
    FileUtils.readLines(file).zipWithIndex.map { case (line, index) =>
      val lineNumber = index + 1
      try {
        val flowIdPlaceholder = FlowIdPlaceholderPattern.findFirstMatchIn(line).map(_.group(1))
        val unboundFlowIdPattern = flowIdPlaceholder.map { token =>
          Pattern.compile(line.replace(s"flowId='<$token>'", "flowId='[^']*'"))
        }
        ExpectedPattern(file, lineNumber, line, Pattern.compile(line), flowIdPlaceholder, unboundFlowIdPattern)
      } catch {
        case e: PatternSyntaxException =>
          throw new IllegalArgumentException(s"Invalid regex pattern in ${normalisedAbsolutePath(file)}:$lineNumber: $line", e)
      }
    }

  private def echoOutputForDiagnostics(outputLines: Seq[String]): Unit =
    outputLines.foreach { line =>
      // Echo inert service messages for humans while preserving raw lines for regex fixture matching.
      println(TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput(line))
    }

  private final case class ExpectedPattern(
    file: File,
    lineNumber: Int,
    text: String,
    pattern: Pattern,
    flowIdPlaceholder: Option[String],
    unboundFlowIdPattern: Option[Pattern]
  ) {
    private val boundFlowIdPatterns = mutable.Map.empty[String, Pattern]

    def matches(line: String): Boolean =
      pattern.matcher(line).find()

    def hasUnboundFlowId(bindings: Map[String, String]): Boolean =
      flowIdPlaceholder.exists(token => !bindings.contains(token))

    /** Matches one output line and applies this pattern's file-local flow-ID binding rule, if present. */
    def matchLine(line: String, bindings: Map[String, String]): RequiredPatternMatch = flowIdPlaceholder match {
      case None if matches(line) =>
        RequiredPatternMatch.Matched(bindings)
      case None =>
        RequiredPatternMatch.NotMatched
      case Some(token) =>
        bindings.get(token) match {
          case Some(flowId) if boundFlowIdPattern(flowId).matcher(line).find() =>
            RequiredPatternMatch.Matched(bindings)
          case Some(_) =>
            RequiredPatternMatch.NotMatched
          case None if unboundFlowIdPattern.get.matcher(line).find() =>
            // The generic pattern preserves every non-flow regex constraint; parse the same service message only to
            // obtain the concrete attribute value that the symbolic token must bind.
            parseServiceMessage(line).flatMap(_._2.get("flowId")) match {
              case Some(flowId) =>
                bindings.collectFirst { case (boundToken, boundFlowId) if boundFlowId == flowId => boundToken } match {
                  case Some(boundToken) => RequiredPatternMatch.ConflictingFlowId(token, flowId, boundToken)
                  case None => RequiredPatternMatch.Matched(bindings.updated(token, flowId))
                }
              case None => RequiredPatternMatch.NotMatched
            }
          case None =>
            RequiredPatternMatch.NotMatched
        }
    }

    private def boundFlowIdPattern(flowId: String): Pattern =
      boundFlowIdPatterns.getOrElseUpdate(
        flowId,
        Pattern.compile(text.replace(s"flowId='<${flowIdPlaceholder.get}>'", s"flowId='${Pattern.quote(flowId)}'"))
      )

    def diagnosticText: String =
      s"${normalisedAbsolutePath(file)}:$lineNumber: $text"
  }

  private final case class CompilationLifecycleEvent(started: Boolean, compiler: String, flowId: String, lineIndex: Int)

  private val ServiceMessagePattern = """##teamcity\[([^ ]+)(?: (.*))?\]""".r
  private val AttributePattern = """([^ =]+)='([^']*)'""".r
  private val FlowIdPlaceholderPattern = """flowId='<(flowId[1-9][0-9]*)>'""".r

  private final case class ExpectedPatternGroup(file: File, patterns: Seq[ExpectedPattern])

  private final case class ForbiddenMatch(pattern: ExpectedPattern, outputLine: String) {
    def diagnosticText: String =
      s"${pattern.diagnosticText}${System.lineSeparator()}  matched output: $outputLine"
  }

  private final case class OutputMatch(pattern: ExpectedPattern, outputLineNumber: Int, outputLine: String) {
    def diagnosticText: String =
      s"${pattern.diagnosticText}${System.lineSeparator()}  matched output line $outputLineNumber: $outputLine"
  }

  private sealed trait RequiredPatternMatch

  private object RequiredPatternMatch {
    final case class Matched(bindings: Map[String, String]) extends RequiredPatternMatch
    final case class ConflictingFlowId(token: String, concreteFlowId: String, alreadyBoundTo: String) extends RequiredPatternMatch {
      def diagnosticText: String =
        s"$token cannot bind to '$concreteFlowId': it is already bound to $alreadyBoundTo"
    }
    case object NotMatched extends RequiredPatternMatch
  }

  private final case class RequiredMatchCandidate(outputMatch: OutputMatch, bindings: Map[String, String])

  private final case class RequiredMatchBranch(
    matches: Vector[OutputMatch],
    bindings: Map[String, String],
    flowIdConflict: Option[RequiredPatternMatch.ConflictingFlowId]
  ) {
    def prepend(outputMatch: OutputMatch): RequiredMatchBranch =
      copy(matches = outputMatch +: matches)

    def withFallbackConflict(conflict: Option[RequiredPatternMatch.ConflictingFlowId]): RequiredMatchBranch =
      copy(flowIdConflict = flowIdConflict.orElse(conflict))
  }

  private object RequiredMatchBranch {
    /** Chooses the furthest partial ordered subsequence, preferring one that explains a flow-ID collision on a tie. */
    def best(first: RequiredMatchBranch, second: RequiredMatchBranch): RequiredMatchBranch =
      if (
        second.matches.size > first.matches.size ||
          (second.matches.size == first.matches.size && second.flowIdConflict.nonEmpty && first.flowIdConflict.isEmpty)
      ) second
      else first
  }

  private final case class RequiredMatchResult(
    patterns: ExpectedPatternGroup,
    outputLineCount: Int,
    branch: RequiredMatchBranch
  ) {
    def matchedCount: Int =
      branch.matches.size

    def isComplete: Boolean =
      matchedCount == patterns.patterns.size

    def lastMatch: Option[OutputMatch] =
      branch.matches.lastOption

    def flowIdBindings: Map[String, String] =
      branch.bindings

    def flowIdConflict: Option[RequiredPatternMatch.ConflictingFlowId] =
      branch.flowIdConflict

    def firstMissingPattern: Option[ExpectedPattern] =
      patterns.patterns.lift(matchedCount)
  }
}
