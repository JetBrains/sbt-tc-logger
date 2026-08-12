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
 * @param expectZeroExitCode whether the nested sbt process must finish successfully.
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
  expectZeroExitCode: Boolean = false,
  teamCityEnvironment: Boolean = true,
  expectNoTeamCityMessages: Boolean = false
)
