package org.jetbrains.teamcity.plugins.sbt.logger.utils

import org.jetbrains.sbt.integrationTests.FileUtils
import org.junit.Assert

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.control.NonFatal

/** Stable coordinates and files for one bounded transcript verification. */
private[logger] final case class SbtOutputVerificationInputs(
  lines: Vector[String],
  exitCode: Int,
  expectedResult: SbtProcessResultExpectation,
  runtimeProfile: String,
  scenarioId: String,
  repoRoot: File,
  exactGolden: File,
  transcriptContext: TranscriptContext
) {
  require(scenarioId.matches("[a-z0-9]+(?:-[a-z0-9]+)*"), s"Invalid scenario id '$scenarioId'.")
  SbtOutputVerificationSelection.validateRuntimeProfile(runtimeProfile)
}

/** One assertion emitted by the semantic/hybrid harness after every independent finding has been collected. */
private[logger] final class SbtOutputVerificationException(
  val findings: Vector[SbtSemanticFailure],
  val failureTranscript: File,
  val failureTranscriptWriteError: Option[Throwable]
) extends AssertionError(SbtOutputVerificationException.render(findings, failureTranscript, failureTranscriptWriteError)) {
  require(findings.nonEmpty, "An output verification exception requires at least one finding.")
}

private[logger] object SbtOutputVerificationException {
  private def render(
    findings: Vector[SbtSemanticFailure],
    failureTranscript: File,
    writeError: Option[Throwable]
  ): String = {
    val semantic = new SbtSemanticVerificationException(findings).getMessage
    val artifact = s"Bounded raw transcript: ${FileUtils.normalisedAbsolutePath(failureTranscript)}"
    val artifactStatus = writeError.fold(artifact) { error =>
      s"$artifact (write failed: ${error.getClass.getSimpleName}: ${Option(error.getMessage).getOrElse("no details")})"
    }
    s"$semantic\n$artifactStatus"
  }
}

/** Dispatches an already bounded run without deriving the selected verification mode from its output. */
private[logger] object SbtOutputVerificationDispatcher {
  def verify(mode: SbtOutputVerification, inputs: SbtOutputVerificationInputs): Unit = mode match {
    case SbtOutputVerification.ExactTranscript =>
      withExactFailureArtifact(inputs) {
        SbtOutputVerifier.verify(inputs.lines, inputs.exactGolden, inputs.transcriptContext)
        assertProcessResult(inputs)
      }
    case SbtOutputVerification.Semantic(contract) =>
      verifyCollected(contract, inputs)
    case SbtOutputVerification.Hybrid(contract, plainOutput) =>
      verifyCollected(contract.copy(plainOutput = plainOutput), inputs)
  }

  /** Candidate generation is an explicit exact-transcript operation and remains available for every selected mode. */
  def generateExactCandidate(inputs: SbtOutputVerificationInputs, destination: File): Unit =
    withExactFailureArtifact(inputs) {
      SbtOutputVerifier.writeCandidate(inputs.lines, destination, inputs.transcriptContext)
    }

  /** Kept separate so the harness can report a successfully written candidate before rejecting the process result. */
  def verifyExpectedProcessResult(inputs: SbtOutputVerificationInputs): Unit =
    withExactFailureArtifact(inputs) {
      assertProcessResult(inputs)
    }

  def failureTranscriptFile(repoRoot: File, runtimeProfile: String, scenarioId: String): File =
    new File(
      repoRoot,
      s"target/integration-tests/failure-artifacts/$runtimeProfile/$scenarioId/bounded-transcript.txt"
    )

  private def verifyCollected(contract: SbtSemanticContract, inputs: SbtOutputVerificationInputs): Unit = {
    val effectiveContract = contract.copy(processResult = processResultContract(inputs.expectedResult))
    val findings = SbtSemanticOutputVerifier.collect(
      inputs.lines,
      effectiveContract,
      inputs.exitCode,
      loggerVersion = Some(inputs.transcriptContext.loggerVersion)
    )
    if (findings.nonEmpty) {
      val artifact = attemptFailureTranscript(inputs)
      val failure = new SbtOutputVerificationException(findings, artifact.destination, artifact.writeError)
      artifact.writeError.foreach(failure.addSuppressed)
      throw failure
    }
  }

  private def processResultContract(expectation: SbtProcessResultExpectation): ProcessResultContract = expectation match {
    case SbtProcessResultExpectation.Success => ProcessResultContract.Success
    case SbtProcessResultExpectation.Failure => ProcessResultContract.Failure
  }

  private def assertProcessResult(inputs: SbtOutputVerificationInputs): Unit = inputs.expectedResult match {
    case SbtProcessResultExpectation.Success =>
      Assert.assertEquals(s"Scenario '${inputs.scenarioId}' must succeed", 0, inputs.exitCode)
    case SbtProcessResultExpectation.Failure =>
      Assert.assertTrue(
        s"Scenario '${inputs.scenarioId}' must fail, but exited with 0",
        inputs.exitCode != 0
      )
  }

  private def withExactFailureArtifact(inputs: SbtOutputVerificationInputs)(action: => Unit): Unit = try {
    action
  } catch {
    case failure: AssertionError =>
      val artifact = attemptFailureTranscript(inputs)
      failure.addSuppressed(new AssertionError(
        s"Bounded raw transcript: ${FileUtils.normalisedAbsolutePath(artifact.destination)}"
      ))
      artifact.writeError.foreach(failure.addSuppressed)
      throw failure
  }

  private final case class FailureTranscriptAttempt(destination: File, writeError: Option[Throwable])

  private def attemptFailureTranscript(inputs: SbtOutputVerificationInputs): FailureTranscriptAttempt = {
    val destination = failureTranscriptFile(inputs.repoRoot, inputs.runtimeProfile, inputs.scenarioId)
    val error = try {
      Files.createDirectories(destination.toPath.getParent)
      val content =
        if (inputs.lines.isEmpty) ""
        else inputs.lines.mkString("", "\n", "\n")
      Files.writeString(destination.toPath, content, StandardCharsets.UTF_8)
      None
    } catch {
      case NonFatal(failure) => Some(failure)
    }
    FailureTranscriptAttempt(destination, error)
  }
}
