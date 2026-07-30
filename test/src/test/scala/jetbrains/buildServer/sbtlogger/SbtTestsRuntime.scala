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
  val Sbt100: SbtTestsRuntime = SbtTestsRuntime("1.0", "test/testdata/1.0", "2.12", "1.0", "1.0.0", "1.0.0")

  val All: Seq[SbtTestsRuntime] = Seq(Sbt100)
}
