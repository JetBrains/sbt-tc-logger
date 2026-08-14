package jetbrains.buildServer.sbtlogger

/**
 * Describes one sbt runtime covered by the integration-test harness.
 *
 * The runtime metadata selects the fixture root and the staged logger JAR that nested sbt should load.
 *
 * @param id                      identifier used for isolated fixture workspaces and runtime-specific SBT directories.
 * @param sbtVersion              exact SBT version rendered into the copied fixture before launching nested SBT.
 * @param jdk                     exact JDK major version used to launch nested SBT.
 * @param testDataRelativePath    path from the repository root to this runtime's fixture directory.
 * @param sbtBinaryVersion        sbt plugin binary version used to select the staged logger JAR.
 * @param launcherVersion         sbt launcher version used to start nested sbt and select its global directories.
 */
final case class SbtTestsRuntime(
  id: String,
  sbtVersion: String,
  jdk: SbtTestJdk,
  testDataRelativePath: String,
  sbtBinaryVersion: String,
  launcherVersion: String
)

/**
 * Exact JDK majors covered by the nested-SBT integration tests.
 *
 * These names intentionally do not track a moving "current" JDK. Updating one
 * of the selected JDKs is a visible matrix change and therefore requires a
 * corresponding concrete test-class rename.
 */
enum SbtTestJdk(val id: String, val majorVersion: Int) {
  case Jdk8 extends SbtTestJdk("jdk8", 8)
  case Jdk17 extends SbtTestJdk("jdk17", 17)
}

/**
 * Runtime catalog for all SBT versions that the integration-test harness exercises.
 *
 * Fixture-root names express their minimum supported SBT version: `testdata/1.0` is the legacy SBT 1.0 corpus,
 * `testdata/1.3+` is the modern SBT 1 corpus, and `testdata/2.0+` is the SBT 2 corpus. A scenario whose support begins
 * later can select a dedicated root such as `testdata/1.9+`. Every fixture contains
 * [[org.jetbrains.sbt.integrationTests.SbtFixtureWorkspace.SbtVersionTemplate]], which is rendered with
 * [[SbtTestsRuntime.sbtVersion]] only in the isolated copied workspace.
 */
object SbtTestsRuntime {
  private[sbtlogger] val LatestSbt1Version = "1.12.15"
  private[sbtlogger] val LatestSbt2Version = "2.0.6"

  private enum SbtLine(
    val sbtBinaryVersion: String,
    val launcherVersion: String
    ) {
    case Sbt1 extends SbtLine(
      sbtBinaryVersion = "1.0",
      launcherVersion = LatestSbt1Version
    )
    case Sbt2 extends SbtLine(
      sbtBinaryVersion = "2", // SBT 2 publishes plugins under sbt-2, rather than sbt-2.0.
      launcherVersion = LatestSbt2Version
    )
  }

  private val SbtVersionPattern = """^([0-9]+)(?:\.[0-9]+)*(?:[-+][0-9A-Za-z][0-9A-Za-z.+-]*)?$""".r

  /**
   * Creates metadata for one selected SBT/JDK/fixture-root combination while selecting the current launcher for its
   * supported SBT line.
   *
   * @param sbtVersion stable or prerelease SBT 1.x or 2.x version used as the runtime identifier.
   * @param jdk exact JDK used by the nested SBT process.
   * @param testDataRelativePath fixture root whose minimum SBT version is compatible with `sbtVersion`.
   * @throws IllegalArgumentException when the version is malformed or belongs to an unsupported SBT line.
   */
  private[sbtlogger] def forSbtVersion(
    sbtVersion: String,
    jdk: SbtTestJdk,
    testDataRelativePath: String
  ): SbtTestsRuntime = {
    val line = sbtVersion match {
      case SbtVersionPattern("1") => SbtLine.Sbt1
      case SbtVersionPattern("2") => SbtLine.Sbt2
      case _ => throw new IllegalArgumentException(s"Unsupported SBT version '$sbtVersion'; supported lines are 1.x and 2.x.")
    }

    SbtTestsRuntime(
      id = s"$sbtVersion-${jdk.id}",
      sbtVersion = sbtVersion,
      jdk = jdk,
      testDataRelativePath = testDataRelativePath,
      sbtBinaryVersion = line.sbtBinaryVersion,
      launcherVersion = line.launcherVersion
    )
  }

  // See the latest versions here:
  //  - https://www.scala-sbt.org/download/
  //  - https://github.com/sbt/sbt/releases
  // This is deliberately a compatibility matrix, not a full SBT × JDK cross-product. The legacy baseline proves
  // SBT 1.0/JDK 8 support; the current SBT 1 suite exercises both selected JDKs; and SBT 2 exercises JDK 17.
  // Running all combinations adds time and maintenance cost without materially improving coverage.
  val Sbt_1_0_0_Jdk8: SbtTestsRuntime =
    forSbtVersion("1.0.0", SbtTestJdk.Jdk8, "test/testdata/1.0")
  val Sbt1_Latest_Jdk8: SbtTestsRuntime =
    forSbtVersion(LatestSbt1Version, SbtTestJdk.Jdk8, "test/testdata/1.3+")
  val Sbt1_Latest_Jdk17: SbtTestsRuntime =
    forSbtVersion(LatestSbt1Version, SbtTestJdk.Jdk17, "test/testdata/1.3+")
  val Sbt2_Latest_Jdk17: SbtTestsRuntime =
    forSbtVersion(LatestSbt2Version, SbtTestJdk.Jdk17, "test/testdata/2.0+")

  val All: Seq[SbtTestsRuntime] = Seq(
    Sbt_1_0_0_Jdk8,
    Sbt1_Latest_Jdk8,
    Sbt1_Latest_Jdk17,
    Sbt2_Latest_Jdk17
  )
}
