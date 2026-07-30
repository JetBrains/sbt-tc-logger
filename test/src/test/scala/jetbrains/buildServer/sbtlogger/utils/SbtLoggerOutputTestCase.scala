package jetbrains.buildServer.sbtlogger.utils

/**
 * Describes one fixture-backed logger-output scenario.
 *
 * @param fixture            fixture directory under the selected runtime test-data root.
 * @param sbtCommands        sbt commands sent through the nested process command transport.
 * @param sbtOptions         launcher command-line options passed before the command transport.
 * @param outputFiles        expected output regex files to check; defaults to `output.txt` when empty.
 * @param expectZeroExitCode whether the nested sbt process must finish successfully.
 * @param teamCityEnvironment whether the nested process receives TEAMCITY_VERSION.
 * @param expectNoTeamCityMessages asserts the logger remains completely inactive when TeamCity is absent.
 */
final case class SbtLoggerOutputTestCase(
  fixture: String,
  sbtCommands: Seq[String],
  sbtOptions: Seq[String] = Seq("--error"),
  outputFiles: Seq[String] = Seq.empty,
  expectZeroExitCode: Boolean = false,
  teamCityEnvironment: Boolean = true,
  expectNoTeamCityMessages: Boolean = false
)
