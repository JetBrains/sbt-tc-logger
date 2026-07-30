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
  // SBT 2 reports its plugin binary version as `2`, while the runtime line and
  // fixture directory remain `2.0`. Keep those concepts separate so the harness
  // locates the jar that the build actually packages.
  val Sbt200: SbtTestsRuntime = SbtTestsRuntime("2.0", "test/testdata/2.0", "3", "2", "2.0.4", "2.0.4")

  val All: Seq[SbtTestsRuntime] = Seq(Sbt100, Sbt200)
}
