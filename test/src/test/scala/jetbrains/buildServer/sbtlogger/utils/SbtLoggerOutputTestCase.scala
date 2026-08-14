package jetbrains.buildServer.sbtlogger.utils

/**
 * Describes one fixture-backed logger-output scenario.
 *
 * @param fixture            fixture directory under the selected runtime test-data root.
 * @param fixtureRootRelativePath optional fixture-root override, relative to the repository root. This is for scenarios
 *                                whose support starts later than the runtime's baseline fixture corpus.
 * @param sbtCommands        sbt commands sent through the nested process command transport.
 * @param sbtOptions         launcher command-line options passed before the command transport.
 * @param outputFiles        expected output regex files to check; defaults to `output.txt` when empty.
 * @param expectedExitCode   expected result of the nested sbt process.
 * @param compilationLifecycle optional strict lifecycle contract for compilation TeamCity messages.
 * @param teamCityEnvironment whether the nested process receives `TEAMCITY_VERSION`; when false, an inherited value is
 *                            removed before the process starts.
 * @param expectNoTeamCityMessages asserts the logger remains completely inactive when TeamCity is absent.
 */
final case class SbtLoggerOutputTestCase(
  fixture: String,
  fixtureRootRelativePath: Option[String] = None,
  sbtCommands: Seq[String],
  sbtOptions: Seq[String] = Seq("--error"),
  outputFiles: Seq[String] = Seq.empty,
  expectedExitCode: SbtExitCodeExpectation = SbtExitCodeExpectation.Any,
  compilationLifecycle: Option[SbtCompilationLifecycleExpectation] = None,
  teamCityEnvironment: Boolean = true,
  expectNoTeamCityMessages: Boolean = false
)

/** Exit-code contract for a fixture-backed nested sbt invocation. */
sealed trait SbtExitCodeExpectation

object SbtExitCodeExpectation {
  /** The scenario's output is relevant but its exit status is not. */
  case object Any extends SbtExitCodeExpectation
  /** The command must complete successfully. */
  case object Zero extends SbtExitCodeExpectation
  /** The command must fail, proving a captured compilation failure was rethrown. */
  case object NonZero extends SbtExitCodeExpectation
}

/** Strict lifecycle requirements for a focused compilation-regression fixture. */
final case class SbtCompilationLifecycleExpectation(
  expectedClosures: Int,
  expectedLegacyErrorSummariesBeforeFinish: Option[Int] = None
) {
  require(expectedClosures > 0, "A lifecycle regression fixture must require at least one closure.")
  require(expectedLegacyErrorSummariesBeforeFinish.forall(_ >= 0), "The number of expected legacy error summaries cannot be negative.")
}
