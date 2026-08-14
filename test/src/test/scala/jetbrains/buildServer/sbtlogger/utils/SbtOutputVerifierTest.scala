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
