package jetbrains.buildServer.sbtlogger.utils
import org.jetbrains.sbt.integrationTests.FileUtils
import org.jetbrains.sbt.integrationTests.FileUtils.normalisedAbsolutePath
import org.junit.Assert

import java.io.File
import java.util.regex.{Pattern, PatternSyntaxException}

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
   * Unrelated output lines may appear between required matches.
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

    val legacyErrorSummaries = lines.zipWithIndex.collect {
      case (line, lineIndex) if parseServiceMessage(line).exists { case (name, attributes) =>
        name == "message" &&
          attributes.get("status").contains("ERROR") &&
          attributes.get("flowId").isDefined &&
          attributes.get("text").contains("one error found")
      } =>
        val (_, attributes) = parseServiceMessage(line).get
        CompilationErrorSummary(attributes("flowId"), lineIndex)
    }
    expectation.expectedLegacyErrorSummariesBeforeFinish.foreach { expectedCount =>
      Assert.assertEquals(s"Expected $expectedCount legacy compiler error summaries", expectedCount, legacyErrorSummaries.size)
    }
    expectation.expectedLegacyErrorSummariesBeforeFinish.foreach { _ =>
      legacyErrorSummaries.foreach { summary =>
        val matchingLifecycles = grouped.collect {
          case ((compiler, flowId), events) if flowId == summary.flowId =>
            (compiler, events.filter(_.started).head, events.filterNot(_.started).head)
        }.filter { case (_, start, finish) =>
          start.lineIndex < summary.lineIndex && summary.lineIndex < finish.lineIndex
        }
        Assert.assertEquals(
          s"Expected one active compiler lifecycle for legacy error summary flowId='${summary.flowId}'",
          1,
          matchingLifecycles.size
        )
      }
    }
  }

  /**
   * Applies the verifier contract to already-split output lines.
   *
   * Excludes are checked first so forbidden output is reported even when no required file is supplied. Required files are
   * checked independently: each file describes one acceptable ordered subsequence that must be present in the same full
   * output.
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

  private def parseServiceMessage(line: String): Option[(String, Map[String, String])] = line match {
    case ServiceMessagePattern(name, rawAttributes) =>
      Some(name -> AttributePattern.findAllMatchIn(rawAttributes).map { attribute =>
        attribute.group(1) -> attribute.group(2)
      }.toMap)
    case _ => None
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
   * Counts how many required patterns were matched as an ordered subsequence of the output.
   *
   * Matching advances by at most one required pattern per output line. This preserves the historical fixture semantics:
   * one line cannot satisfy two consecutive expected lines, even if it contains text that would match both regexes.
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
    var matchedCount = 0
    var lastMatch: Option[OutputMatch] = None

    for ((line, outputLineIndex) <- allLines.iterator.zipWithIndex if matchedCount < requiredPatterns.patterns.size) {
      val currentPattern = requiredPatterns.patterns(matchedCount)
      if (currentPattern.matches(line)) {
        lastMatch = Some(OutputMatch(currentPattern, outputLineIndex + 1, line))
        matchedCount += 1
      }
    }

    RequiredMatchResult(requiredPatterns, matchedCount, allLines.size, lastMatch)
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
        ExpectedPattern(file, lineNumber, line, Pattern.compile(line))
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

  private final case class ExpectedPattern(file: File, lineNumber: Int, text: String, pattern: Pattern) {
    def matches(line: String): Boolean =
      pattern.matcher(line).find()

    def diagnosticText: String =
      s"${normalisedAbsolutePath(file)}:$lineNumber: $text"
  }

  private final case class CompilationLifecycleEvent(started: Boolean, compiler: String, flowId: String, lineIndex: Int)
  private final case class CompilationErrorSummary(flowId: String, lineIndex: Int)

  private val ServiceMessagePattern = """##teamcity\[([^ ]+)(?: (.*))?\]""".r
  private val AttributePattern = """([^ =]+)='([^']*)'""".r

  private final case class ExpectedPatternGroup(file: File, patterns: Seq[ExpectedPattern])

  private final case class ForbiddenMatch(pattern: ExpectedPattern, outputLine: String) {
    def diagnosticText: String =
      s"${pattern.diagnosticText}${System.lineSeparator()}  matched output: $outputLine"
  }

  private final case class OutputMatch(pattern: ExpectedPattern, outputLineNumber: Int, outputLine: String) {
    def diagnosticText: String =
      s"${pattern.diagnosticText}${System.lineSeparator()}  matched output line $outputLineNumber: $outputLine"
  }

  private final case class RequiredMatchResult(
    patterns: ExpectedPatternGroup,
    matchedCount: Int,
    outputLineCount: Int,
    lastMatch: Option[OutputMatch]
  ) {
    def isComplete: Boolean =
      matchedCount == patterns.patterns.size

    def firstMissingPattern: Option[ExpectedPattern] =
      patterns.patterns.lift(matchedCount)
  }
}
