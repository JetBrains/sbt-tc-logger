package org.jetbrains.teamcity.plugins.sbt.logger.utils

import org.jetbrains.sbt.integrationTests.FileUtils
import org.junit.{Assert, Test}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class SbtOutputVerificationDispatcherTest {
  import ObservedServiceMessageKind.BuildLogMessage
  import PlainOutputContract.*
  import SbtOutputVerification.*
  import SemanticValuePattern.exact

  @Test def exactTranscriptRemainsTheDefaultAndUsesTheExistingGoldenContract(): Unit = withEnvironment { environment =>
    val selection = SbtOutputVerificationSelection()
    Assert.assertEquals(ExactTranscript, selection.forRuntimeProfile(environment.runtimeProfile))

    write(environment.golden, "ordinary output\n")
    SbtOutputVerificationDispatcher.verify(
      selection.forRuntimeProfile(environment.runtimeProfile),
      environment.inputs(Vector("ordinary output"), exitCode = 0, SbtProcessResultExpectation.Success)
    )
  }

  @Test def exactCanaryOverridesAreSelectedOnlyByExplicitRuntimeProfile(): Unit = {
    val semantic = Semantic(semanticContract())
    val selection = SbtOutputVerificationSelection.withExactCanaries(
      semantic,
      "sbt-1.4-jdk8",
      "sbt-2-jdk17"
    )

    Assert.assertEquals(ExactTranscript, selection.forRuntimeProfile("sbt-1.4-jdk8"))
    Assert.assertEquals(ExactTranscript, selection.forRuntimeProfile("sbt-2-jdk17"))
    Assert.assertEquals(semantic, selection.forRuntimeProfile("sbt-1-jdk17"))
  }

  @Test def semanticDispatchSupportsSuccessAndAnyNonzeroFailureExpectation(): Unit = withEnvironment { environment =>
    val mode = Semantic(semanticContract())
    val output = Vector(message("expected"))

    SbtOutputVerificationDispatcher.verify(
      mode,
      environment.inputs(output, exitCode = 0, SbtProcessResultExpectation.Success)
    )
    SbtOutputVerificationDispatcher.verify(
      mode,
      environment.inputs(output, exitCode = 17, SbtProcessResultExpectation.Failure)
    )

    val expectedSuccess = expectCompositeFailure {
      SbtOutputVerificationDispatcher.verify(
        mode,
        environment.inputs(output, exitCode = 17, SbtProcessResultExpectation.Success)
      )
    }
    val expectedFailure = expectCompositeFailure {
      SbtOutputVerificationDispatcher.verify(
        mode,
        environment.inputs(output, exitCode = 0, SbtProcessResultExpectation.Failure)
      )
    }
    Assert.assertTrue(expectedSuccess.findings.exists(_.category == SbtVerificationFailureCategory.ProcessResultFailure))
    Assert.assertTrue(expectedFailure.findings.exists(_.category == SbtVerificationFailureCategory.ProcessResultFailure))
  }

  @Test def semanticDispatchReportsSemanticPlainAndProcessMismatchesInOneAssertion(): Unit =
    withEnvironment { environment =>
      val output = Vector(message("wrong"), "undeclared plain output")
      val failure = expectCompositeFailure {
        SbtOutputVerificationDispatcher.verify(
          Semantic(semanticContract()),
          environment.inputs(output, exitCode = 9, SbtProcessResultExpectation.Success)
        )
      }

      Assert.assertTrue(failure.findings.exists(
        _.category == SbtVerificationFailureCategory.SemanticCardinalityFailure))
      Assert.assertTrue(failure.findings.exists(_.category == SbtVerificationFailureCategory.PlainOutputFailure))
      Assert.assertTrue(failure.findings.exists(_.category == SbtVerificationFailureCategory.ProcessResultFailure))
      Assert.assertTrue(failure.getMessage.contains("Semantic output verification found"))
      Assert.assertTrue(failure.getMessage.contains("Bounded raw transcript:"))
    }

  @Test def hybridRequiresAndRunsItsConcretePlainOutputContractWithoutDelegatedEvidence(): Unit =
    withEnvironment { environment =>
      val delegatedSemanticContract = semanticContract().copy(plainOutput = DelegatedToHybrid)
      val mode = Hybrid(delegatedSemanticContract, Exact(Vector("declared plain output")))
      val failure = expectCompositeFailure {
        SbtOutputVerificationDispatcher.verify(
          mode,
          environment.inputs(
            Vector(message("expected"), "different plain output"),
            exitCode = 0,
            SbtProcessResultExpectation.Success
          )
        )
      }

      Assert.assertTrue(failure.findings.exists(_.category == SbtVerificationFailureCategory.PlainOutputFailure))
      Assert.assertFalse(failure.findings.exists(_.disposition == SbtFindingDisposition.Blocked))
      expectIllegalArgument(Hybrid(semanticContract(), Exact(Vector.empty)))
      expectIllegalArgument(Hybrid(delegatedSemanticContract, DelegatedToHybrid))
      expectIllegalArgument(Semantic(delegatedSemanticContract))
    }

  @Test def compositeFailureWritesTheCompleteBoundedRawTranscriptToTheStableArtifactPath(): Unit =
    withEnvironment { environment =>
      val output = Vector(message("wrong"), "raw ## marker without a service message", "last line")
      val failure = expectCompositeFailure {
        SbtOutputVerificationDispatcher.verify(
          Semantic(semanticContract()),
          environment.inputs(output, exitCode = 0, SbtProcessResultExpectation.Success)
        )
      }
      val expected = new File(
        environment.root,
        s"target/integration-tests/failure-artifacts/${environment.runtimeProfile}/${environment.scenarioId}/bounded-transcript.txt"
      )

      Assert.assertEquals(expected.getCanonicalFile, failure.failureTranscript.getCanonicalFile)
      Assert.assertEquals(output.mkString("", "\n", "\n"), Files.readString(expected.toPath, StandardCharsets.UTF_8))
    }

  @Test def explicitCandidateGenerationRemainsAvailableForSemanticScenarios(): Unit = withEnvironment { environment =>
    val destination = new File(environment.root, "candidate.txt")
    val inputs = environment.inputs(
      Vector(message("expected"), "ordinary output"),
      exitCode = 0,
      SbtProcessResultExpectation.Success
    )

    SbtOutputVerificationDispatcher.generateExactCandidate(inputs, destination)

    Assert.assertTrue(destination.isFile)
    SbtOutputVerifier.verify(inputs.lines, destination, inputs.transcriptContext)
  }

  @Test def candidateIsWrittenBeforeUnexpectedSuccessIsRejected(): Unit = withEnvironment { environment =>
    val destination = new File(environment.root, "unexpected-success-candidate.txt")
    val inputs = environment.inputs(
      Vector(message("expected")),
      exitCode = 0,
      SbtProcessResultExpectation.Failure
    )

    SbtOutputVerificationDispatcher.generateExactCandidate(inputs, destination)
    val failure = expectAssertion(SbtOutputVerificationDispatcher.verifyExpectedProcessResult(inputs))

    Assert.assertTrue(destination.isFile)
    Assert.assertTrue(failure.getMessage.contains("must fail"))
  }

  @Test def candidateIsWrittenBeforeUnexpectedFailureIsRejected(): Unit = withEnvironment { environment =>
    val destination = new File(environment.root, "unexpected-failure-candidate.txt")
    val inputs = environment.inputs(
      Vector(message("expected")),
      exitCode = 19,
      SbtProcessResultExpectation.Success
    )

    SbtOutputVerificationDispatcher.generateExactCandidate(inputs, destination)
    val failure = expectAssertion(SbtOutputVerificationDispatcher.verifyExpectedProcessResult(inputs))

    Assert.assertTrue(destination.isFile)
    Assert.assertTrue(failure.getMessage.contains("must succeed"))
  }

  @Test def exactFailureAlsoPersistsTheRawTranscriptWithoutReplacingItsAssertion(): Unit =
    withEnvironment { environment =>
      write(environment.golden, "expected\n")
      val inputs = environment.inputs(
        Vector("actual"),
        exitCode = 0,
        SbtProcessResultExpectation.Success
      )
      val failure = expectAssertion {
        SbtOutputVerificationDispatcher.verify(ExactTranscript, inputs)
      }
      val artifact = SbtOutputVerificationDispatcher.failureTranscriptFile(
        environment.root,
        environment.runtimeProfile,
        environment.scenarioId
      )

      Assert.assertEquals("actual\n", Files.readString(artifact.toPath, StandardCharsets.UTF_8))
      Assert.assertTrue(failure.getSuppressed.exists(_.getMessage.contains("Bounded raw transcript:")))
    }

  @Test def semanticArtifactWriteFailureDoesNotReplaceTheCompositeAssertion(): Unit =
    withEnvironment { environment =>
      blockFailureArtifactDirectory(environment)
      val failure = expectCompositeFailure {
        SbtOutputVerificationDispatcher.verify(
          Semantic(semanticContract()),
          environment.inputs(Vector(message("wrong")), exitCode = 0, SbtProcessResultExpectation.Success)
        )
      }

      Assert.assertTrue(failure.findings.exists(
        _.category == SbtVerificationFailureCategory.SemanticCardinalityFailure))
      Assert.assertTrue(failure.failureTranscriptWriteError.nonEmpty)
      Assert.assertTrue(failure.getMessage.contains("write failed:"))
      Assert.assertTrue(failure.getSuppressed.contains(failure.failureTranscriptWriteError.get))
    }

  @Test def exactArtifactWriteFailureDoesNotReplaceTheOriginalAssertion(): Unit =
    withEnvironment { environment =>
      write(environment.golden, "expected\n")
      blockFailureArtifactDirectory(environment)
      val failure = expectAssertion {
        SbtOutputVerificationDispatcher.verify(
          ExactTranscript,
          environment.inputs(Vector("actual"), exitCode = 0, SbtProcessResultExpectation.Success)
        )
      }

      Assert.assertTrue(failure.getMessage.contains("Exact transcript mismatch"))
      Assert.assertTrue(failure.getSuppressed.exists(_.getMessage.contains("Bounded raw transcript:")))
      Assert.assertTrue(failure.getSuppressed.exists(error =>
        error.isInstanceOf[java.nio.file.FileSystemException]))
    }

  private final case class TestEnvironment(root: File) {
    val runtimeProfile = "sbt-test-jdk17"
    val scenarioId = "dispatcher-test"
    val golden = new File(root, "expected.txt")
    val context = TranscriptContext(root, root, root, root, root, root, root, "test-version")

    def inputs(
      lines: Vector[String],
      exitCode: Int,
      expectation: SbtProcessResultExpectation
    ): SbtOutputVerificationInputs = SbtOutputVerificationInputs(
      lines,
      exitCode,
      expectation,
      runtimeProfile,
      scenarioId,
      root,
      golden,
      context
    )
  }

  private def semanticContract(): SbtSemanticContract = SbtSemanticContract(events = Vector(
    ExpectedSemanticEvent(
      "expected-message",
      BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> exact("expected")
    )
  ))

  private def message(text: String): String =
    s"##teamcity[message status='NORMAL' text='$text']"

  private def withEnvironment(action: TestEnvironment => Unit): Unit = {
    val root = Files.createTempDirectory("sbt-output-verification-dispatcher-").toFile
    try action(TestEnvironment(root))
    finally FileUtils.deleteRecursively(root.toPath)
  }

  private def write(file: File, content: String): Unit =
    Files.writeString(file.toPath, content, StandardCharsets.UTF_8)

  private def blockFailureArtifactDirectory(environment: TestEnvironment): Unit =
    write(new File(environment.root, "target"), "path collision")

  private def expectCompositeFailure(action: => Unit): SbtOutputVerificationException = try {
    action
    throw new AssertionError("Expected composite output verification to fail.")
  } catch {
    case failure: SbtOutputVerificationException => failure
  }

  private def expectAssertion(action: => Unit): AssertionError = try {
    action
    throw new AssertionError("Expected output verification to fail.")
  } catch {
    case failure: SbtOutputVerificationException => throw failure
    case failure: AssertionError => failure
  }

  private def expectIllegalArgument(action: => Any): Unit = try {
    action
    throw new AssertionError("Expected contract construction to fail.")
  } catch {
    case _: IllegalArgumentException => ()
  }
}
