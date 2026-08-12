package jetbrains.buildServer.sbtlogger

/**
 * Describes one sbt runtime covered by the integration-test harness.
 *
 * The runtime metadata selects the fixture root and the staged logger JAR that nested sbt should load.
 *
 * @param id                      identifier used for isolated fixture workspaces and runtime-specific SBT directories.
 * @param testDataRelativePath    path from the repository root to this runtime's fixture directory.
 * @param sbtBinaryVersion        sbt plugin binary version used to select the staged logger JAR.
 * @param launcherVersion         sbt launcher version used to start nested sbt and select its global directories.
 * @param defaultSbtVersion       sbt version used for Java selection when the fixture has no `build.properties` version.
 * @param commandTransport        mechanism for passing options and commands to the nested sbt launcher.
 */
final case class SbtTestsRuntime(
  id: String,
  testDataRelativePath: String,
  sbtBinaryVersion: String,
  launcherVersion: String,
  defaultSbtVersion: String,
  commandTransport: SbtCommandTransport
)

/** Selects how the nested SBT launcher receives its options and commands. */
enum SbtCommandTransport {
  /** Feed the interactive SBT shell through standard input. */
  case StandardInput

  /** Pass the complete non-interactive command sequence to the launcher as an argument. */
  case CommandArgument
}

/**
 * Runtime catalog for all sbt versions that the integration-test harness exercises.
 */
object SbtTestsRuntime {
  val Sbt100: SbtTestsRuntime = SbtTestsRuntime(
    id = "1.0",
    testDataRelativePath = "test/testdata/1.0",
    sbtBinaryVersion = "1.0",
    launcherVersion = "1.0.0",
    defaultSbtVersion = "1.0.0",
    commandTransport = SbtCommandTransport.StandardInput
  )
  // SBT 2 reports its plugin binary version as `2`, while the runtime line and
  // fixture directory remain `2.0`. Keep those concepts separate so the harness
  // locates the jar that the build actually packages.
  val Sbt200: SbtTestsRuntime = SbtTestsRuntime(
    id = "2.0",
    testDataRelativePath = "test/testdata/2.0",
    sbtBinaryVersion = "2",
    launcherVersion = "2.0.4",
    defaultSbtVersion = "2.0.4",
    commandTransport = SbtCommandTransport.CommandArgument
  )

  val All: Seq[SbtTestsRuntime] = Seq(Sbt100, Sbt200)
}
