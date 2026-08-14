package jetbrains.buildServer.sbtlogger.utils

import org.jetbrains.sbt.integrationTests.FileUtils
import org.junit.{Assert, Test}

import java.io.File

/**
 * The test doesn't test business logic.
 *
 * Instead, it tests the implementation of the test utility [[SbtOutputVerifierTest]]
 */
class SbtOutputVerifierTest {

  @Test
  def compilationLifecycleRequiresOneOrderedPairForEachFlow(): Unit =
    SbtOutputVerifier.assertCompilationLifecycle(
      """##teamcity[compilationStarted compiler='Scala compiler' flowId='main']
        |##teamcity[message status='ERROR' flowId='main' text='one error found']
        |##teamcity[compilationFinished compiler='Scala compiler' flowId='main']
        |""".stripMargin,
      SbtCompilationLifecycleExpectation(
        expectedClosures = 1,
        expectedLegacyErrorSummariesBeforeFinish = Some(1)
      )
    )

  @Test
  def compilationLifecycleRejectsDuplicateOrUnmatchedFinishes(): Unit = {
    val duplicateFinish = expectAssertionError {
      SbtOutputVerifier.assertCompilationLifecycle(
        """##teamcity[compilationStarted compiler='Scala compiler' flowId='main']
          |##teamcity[compilationFinished compiler='Scala compiler' flowId='main']
          |##teamcity[compilationFinished compiler='Scala compiler' flowId='main']
          |""".stripMargin,
        SbtCompilationLifecycleExpectation(expectedClosures = 1)
      )
    }
    assertFailureMessageContains(duplicateFinish, "exactly one compilation finish")

    val unmatchedFinish = expectAssertionError {
      SbtOutputVerifier.assertCompilationLifecycle(
        "##teamcity[compilationFinished compiler='Scala compiler' flowId='main']\n",
        SbtCompilationLifecycleExpectation(expectedClosures = 1)
      )
    }
    assertFailureMessageContains(unmatchedFinish, "exactly one compilation start")
  }

  @Test
  def compilationLifecycleRequiresLegacySummaryOnTheSameFlow(): Unit = {
    val error = expectAssertionError {
      SbtOutputVerifier.assertCompilationLifecycle(
        """##teamcity[compilationStarted compiler='Scala compiler' flowId='main']
          |##teamcity[message status='ERROR' flowId='other' text='one error found']
          |##teamcity[compilationFinished compiler='Scala compiler' flowId='main']
          |""".stripMargin,
        SbtCompilationLifecycleExpectation(
          expectedClosures = 1,
          expectedLegacyErrorSummariesBeforeFinish = Some(1)
        )
      )
    }

    assertFailureMessageContains(error, "legacy error summary flowId='other'")
  }

  @Test
  def checkOutputTextAcceptsRequiredPatternsInOrder(): Unit = {
    val required = patternFile("required", "first", "second")

    SbtOutputVerifier.checkOutputText(
      "first\nnoise\nsecond\n",
      excludesFile = None,
      requiredFiles = Seq(required)
    )
  }

  @Test
  def checkOutputTextRejectsForbiddenPatterns(): Unit = {
    val excludes = patternFile("excludes", "forbidden")

    expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        "allowed\nforbidden\n",
        excludesFile = Some(excludes),
        requiredFiles = Seq.empty
      )
    }
  }

  @Test
  def requiredPatternsConsumeAtMostOnePatternPerOutputLine(): Unit = {
    val required = patternFile("required", "first", "second")

    expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        "first second\n",
        excludesFile = None,
        requiredFiles = Seq(required)
      )
    }
  }

  @Test
  def flowIdPlaceholderRequiresRepeatedTokensToUseTheSameConcreteFlow(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testSuiteStarted name='suite' flowId='<flowId1>'\]""",
      """##teamcity\[testStarted name='suite.test' captureStandardOutput='true' flowId='<flowId1>'\]""",
      """##teamcity\[testFinished name='suite.test' duration='.*' flowId='<flowId1>'\]""",
      """##teamcity\[testSuiteFinished name='suite' flowId='<flowId1>'\]"""
    )

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testSuiteStarted name='suite' flowId='thread-A']
        |##teamcity[testStarted name='suite.test' captureStandardOutput='true' flowId='thread-A']
        |##teamcity[testFinished name='suite.test' duration='7' flowId='thread-A']
        |##teamcity[testSuiteFinished name='suite' flowId='thread-A']
        |""".stripMargin,
      excludesFile = None,
      requiredFiles = Seq(required)
    )
  }

  @Test
  def flowIdPlaceholderRejectsADifferentConcreteFlowForARepeatedToken(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testSuiteStarted name='suite' flowId='<flowId1>'\]""",
      """##teamcity\[testSuiteFinished name='suite' flowId='<flowId1>'\]"""
    )

    val error = expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        """##teamcity[testSuiteStarted name='suite' flowId='first-thread']
          |##teamcity[testSuiteFinished name='suite' flowId='second-thread']
          |""".stripMargin,
        excludesFile = None,
        requiredFiles = Seq(required)
      )
    }

    assertFailureMessageContains(error, "flowId1 = 'first-thread'")
    assertFailureMessageContains(error, "First missing pattern:")
  }

  @Test
  def flowIdPlaceholdersRequireDifferentTokensToUseDifferentConcreteFlows(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testSuiteStarted name='first' flowId='<flowId1>'\]""",
      """##teamcity\[testSuiteStarted name='second' flowId='<flowId2>'\]"""
    )

    val error = expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        """##teamcity[testSuiteStarted name='first' flowId='shared-thread']
          |##teamcity[testSuiteStarted name='second' flowId='shared-thread']
          |""".stripMargin,
        excludesFile = None,
        requiredFiles = Seq(required)
      )
    }

    assertFailureMessageContains(error, "flowId1 = 'shared-thread'")
    assertFailureMessageContains(error, "flowId2 cannot bind to 'shared-thread': it is already bound to flowId1")
  }

  @Test
  def flowIdPlaceholderBindingsAreScopedToOneRequiredFile(): Unit = {
    val firstRequired = patternFile("first-required", """##teamcity\[testSuiteStarted name='first' flowId='<flowId1>'\]""")
    val secondRequired = patternFile("second-required", """##teamcity\[testSuiteStarted name='second' flowId='<flowId1>'\]""")

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testSuiteStarted name='first' flowId='shared-thread']
        |##teamcity[testSuiteStarted name='second' flowId='shared-thread']
        |""".stripMargin,
      excludesFile = None,
      requiredFiles = Seq(firstRequired, secondRequired)
    )
  }

  @Test
  def flowIdPlaceholderSearchBacktracksToALaterCompatibleOrderedSubsequence(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testStarted name='suite.test' captureStandardOutput='true' flowId='<flowId7>'\]""",
      """##teamcity\[testFinished name='suite.test' duration='.*' flowId='<flowId7>'\]"""
    )

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testStarted name='suite.test' captureStandardOutput='true' flowId='first-thread']
        |##teamcity[testStarted name='suite.test' captureStandardOutput='true' flowId='second-thread']
        |##teamcity[testFinished name='suite.test' duration='1' flowId='second-thread']
        |""".stripMargin,
      excludesFile = None,
      requiredFiles = Seq(required)
    )
  }

  @Test
  def flowIdPlaceholderRecognisesAServiceMessageWithTrailingConsoleOutput(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testStarted name='suite.test' captureStandardOutput='true'.* flowId='<flowId1>'\]""",
      """##teamcity\[testFinished name='suite.test'.* flowId='<flowId1>'\]"""
    )

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testStarted name='suite.test' captureStandardOutput='true' flowId='worker-7'][info] suite started
        |##teamcity[testFinished name='suite.test' duration='4' flowId='worker-7'][info] suite finished
        |""".stripMargin,
      excludesFile = None,
      requiredFiles = Seq(required)
    )
  }

  @Test
  def requiredPatternFailureReportsUsefulMatchContext(): Unit = {
    val required = patternFile("required", "first", "second")

    val error = expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        "first\nunexpected\n",
        excludesFile = None,
        requiredFiles = Seq(required)
      )
    }

    assertFailureMessageContains(error, "Matched 1/2 patterns across 2 captured output lines.")
    assertFailureMessageContains(error, "Last matched pattern:")
    assertFailureMessageContains(error, "matched output line 1: first")
    assertFailureMessageContains(error, "First missing pattern:")
    assertFailureMessageContains(error, "See the build log for the complete nested-sbt output.")
  }

  private def patternFile(prefix: String, lines: String*): File = {
    val file = FileUtils.createTempFile(prefix, ".txt")
    FileUtils.writeLinesTo(file, lines*)
    file
  }

  private def expectAssertionError(block: => Unit): AssertionError = {
    try {
      block
    } catch {
      case e: AssertionError =>
        return e
    }

    Assert.fail("Expected the verifier to throw AssertionError, but it completed successfully")
    throw new AssertionError("unreachable")
  }

  private def assertFailureMessageContains(error: AssertionError, expectedText: String): Unit = {
    val actualMessage = Option(error.getMessage).getOrElse("<no failure message>")
    Assert.assertTrue(
      s"Expected verifier failure message to contain '$expectedText', but was: $actualMessage",
      actualMessage.contains(expectedText)
    )
  }
}
