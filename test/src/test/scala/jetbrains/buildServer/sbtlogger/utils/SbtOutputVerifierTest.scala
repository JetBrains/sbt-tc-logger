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
        errorSummaryCompilerBeforeFinish = Some("Scala compiler")
      )
    )
  }

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
    Assert.assertTrue(duplicateFinish.getMessage.contains("exactly one compilation finish"))

    val unmatchedFinish = expectAssertionError {
      SbtOutputVerifier.assertCompilationLifecycle(
        "##teamcity[compilationFinished compiler='Scala compiler' flowId='main']\n",
        SbtCompilationLifecycleExpectation(expectedClosures = 1)
      )
    }
    Assert.assertTrue(unmatchedFinish.getMessage.contains("exactly one compilation start"))
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
          errorSummaryCompilerBeforeFinish = Some("Scala compiler")
        )
      )
    }

    Assert.assertTrue(error.getMessage.contains("legacy compiler error summary"))
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

    val message = error.getMessage
    Assert.assertTrue(message.contains("Matched 1/2 patterns across 2 captured output lines."))
    Assert.assertTrue(message.contains("Last matched pattern:"))
    Assert.assertTrue(message.contains("matched output line 1: first"))
    Assert.assertTrue(message.contains("First missing pattern:"))
    Assert.assertTrue(message.contains("See the build log for the complete nested-sbt output."))
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

    Assert.fail("Expected AssertionError")
    throw new AssertionError("unreachable")
  }
}
