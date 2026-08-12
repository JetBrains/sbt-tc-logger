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
 * @param commandTransport        mechanism for passing options and commands to the nested sbt launcher.
 */
final case class SbtTestsRuntime(
  id: String,
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
 * Runtime catalog for all sbt versions that the integration-test harness exercises.
 */
object SbtTestsRuntime {
  private enum SbtLine(
    val testDataRelativePath: String,
    val sbtBinaryVersion: String,
    val launcherVersion: String,
    val commandTransport: SbtCommandTransport
  ) {
    case Sbt1 extends SbtLine(
      testDataRelativePath = "test/testdata/1.0",
      sbtBinaryVersion = "1.0",
      launcherVersion = "1.12.15",
      commandTransport = SbtCommandTransport.StandardInput
    )
    case Sbt2 extends SbtLine(
      testDataRelativePath = "test/testdata/2.0",
      sbtBinaryVersion = "2", // SBT 2 publishes plugins under sbt-2, rather than sbt-2.0.
      launcherVersion = "2.0.6",
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
      testDataRelativePath = line.testDataRelativePath,
      sbtBinaryVersion = line.sbtBinaryVersion,
      launcherVersion = line.launcherVersion,
      commandTransport = line.commandTransport
    )
  }

  // See the latest versions here:
  //  - https://www.scala-sbt.org/download/
  //  - https://github.com/sbt/sbt/releases
  val Sbt100: SbtTestsRuntime = forSbtVersion("1.0.0")
  val Sbt200: SbtTestsRuntime = forSbtVersion("2.0.4")

  val All: Seq[SbtTestsRuntime] = Seq(Sbt100, Sbt200)
}
