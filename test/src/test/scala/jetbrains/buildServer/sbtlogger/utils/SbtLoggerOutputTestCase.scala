package jetbrains.buildServer.sbtlogger.utils

/**
 * Describes one fixture-backed logger-output scenario.
 *
 * @param fixture            fixture directory under the selected runtime test-data root.
 * @param sbtCommands        sbt commands written to the nested process command file.
 * @param sbtOptions         command-line options passed before the command file.
 * @param outputFiles        expected output regex files to check; defaults to `output.txt` when empty.
 * @param expectZeroExitCode whether the nested sbt process must finish successfully.
 */
final case class SbtLoggerOutputTestCase(
  fixture: String,
  sbtCommands: Seq[String],
  sbtOptions: Seq[String] = Seq("--error"),
  outputFiles: Seq[String] = Seq.empty,
  expectZeroExitCode: Boolean = false
)
