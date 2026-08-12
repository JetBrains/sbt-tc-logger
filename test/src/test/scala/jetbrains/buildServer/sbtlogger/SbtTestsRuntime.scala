package jetbrains.buildServer.sbtlogger

/**
 * Describes one sbt runtime covered by the integration-test harness.
 *
 * The runtime metadata selects the fixture root and the staged logger JAR that nested sbt should load.
 *
 * @param id                      identifier used for isolated fixture workspaces and runtime-specific SBT directories.
 * @param sbtVersion              exact SBT version rendered into the copied fixture before launching nested SBT.
 * @param testDataRelativePath    path from the repository root to this runtime's fixture directory.
 * @param sbtBinaryVersion        sbt plugin binary version used to select the staged logger JAR.
 * @param launcherVersion         sbt launcher version used to start nested sbt and select its global directories.
 * @param commandTransport        mechanism for passing options and commands to the nested sbt launcher.
 */
final case class SbtTestsRuntime(
  id: String,
  sbtVersion: String,
  testDataRelativePath: String,
  sbtBinaryVersion: String,
  launcherVersion: String,
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
 * Runtime catalog for all SBT versions that the integration-test harness exercises.
 *
 * The `testdata/1.0+` and `testdata/2.0+` directory names identify plugin binary-version lines, not exact nested SBT
 * releases. A scenario whose support begins later can select a dedicated root such as `testdata/1.9+`. Every fixture
 * contains [[org.jetbrains.sbt.integrationTests.SbtFixtureWorkspace.SbtVersionTemplate]], which is rendered with
 * [[SbtTestsRuntime.sbtVersion]] only in the isolated copied workspace.
 */
object SbtTestsRuntime {
  private[sbtlogger] val LatestSbt1Version = "1.12.15"
  private[sbtlogger] val LatestSbt2Version = "2.0.6"

  private enum SbtLine(
    val testDataRelativePath: String,
    val sbtBinaryVersion: String,
    val launcherVersion: String,
    val commandTransport: SbtCommandTransport
  ) {
    case Sbt1 extends SbtLine(
      testDataRelativePath = "test/testdata/1.0+",
      sbtBinaryVersion = "1.0",
      launcherVersion = LatestSbt1Version,
      commandTransport = SbtCommandTransport.StandardInput
    )
    case Sbt2 extends SbtLine(
      testDataRelativePath = "test/testdata/2.0+",
      sbtBinaryVersion = "2", // SBT 2 publishes plugins under sbt-2, rather than sbt-2.0.
      launcherVersion = LatestSbt2Version,
      commandTransport = SbtCommandTransport.CommandArgument
    )
  }

  private val SbtVersionPattern = """^([0-9]+)(?:\.[0-9]+)*(?:[-+][0-9A-Za-z][0-9A-Za-z.+-]*)?$""".r

  /**
   * Creates metadata for an SBT runtime while selecting the current launcher for its supported SBT line.
   *
   * @param sbtVersion stable or prerelease SBT 1.x or 2.x version used as the runtime identifier.
   * @throws IllegalArgumentException when the version is malformed or belongs to an unsupported SBT line.
   */
  private[sbtlogger] def forSbtVersion(sbtVersion: String): SbtTestsRuntime = {
    val line = sbtVersion match {
      case SbtVersionPattern("1") => SbtLine.Sbt1
      case SbtVersionPattern("2") => SbtLine.Sbt2
      case _ => throw new IllegalArgumentException(s"Unsupported SBT version '$sbtVersion'; supported lines are 1.x and 2.x.")
    }

    SbtTestsRuntime(
      id = sbtVersion,
      sbtVersion = sbtVersion,
      testDataRelativePath = line.testDataRelativePath,
      sbtBinaryVersion = line.sbtBinaryVersion,
      launcherVersion = line.launcherVersion,
      commandTransport = line.commandTransport
    )
  }

  // See the latest versions here:
  //  - https://www.scala-sbt.org/download/
  //  - https://github.com/sbt/sbt/releases
  val Sbt_1_0_0: SbtTestsRuntime = forSbtVersion("1.0.0")
  val Sbt1_Latest: SbtTestsRuntime = forSbtVersion(LatestSbt1Version)
  val Sbt2_Latest: SbtTestsRuntime = forSbtVersion(LatestSbt2Version)

  val All: Seq[SbtTestsRuntime] = Seq(Sbt_1_0_0, Sbt1_Latest, Sbt2_Latest)
}
