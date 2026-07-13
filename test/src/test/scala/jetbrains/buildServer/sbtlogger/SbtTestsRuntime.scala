package jetbrains.buildServer.sbtlogger

/**
 * Describes one sbt runtime covered by the integration-test harness.
 *
 * The runtime metadata selects the fixture root and the assembled plugin jar that nested sbt should load.
 */
final case class SbtTestsRuntime(
  id: String,
  testDataRelativePath: String,
  scalaBinaryVersion: String,
  sbtBinaryVersion: String,
  launcherVersion: String,
  defaultSbtVersion: String
)

/**
 * Runtime catalog for all sbt versions that the integration-test harness exercises.
 */
object SbtTestsRuntime {
  val Sbt013: SbtTestsRuntime = SbtTestsRuntime("0.13", "test/testdata/0.13", "2.10", "0.13", "0.13.17", "0.13.17")
  val Sbt100: SbtTestsRuntime = SbtTestsRuntime("1.0", "test/testdata/1.0", "2.12", "1.0", "1.0.0", "1.0.0")

  val All: Seq[SbtTestsRuntime] = Seq(Sbt013, Sbt100)
}
