import sbt.Def
import sbtassembly.AssemblyPlugin.autoImport.*

val ScalaVersion_212 = "2.12.21"
val ScalaVersion_3 = "3.8.4"

val SbtVersion_1xx = "1.12.12"
val SbtVersion_2xx = "2.0.0"

lazy val logger: Project = (project in file("."))
  .aggregate(
    integrationTests
  )
  .settings(
    name := "sbt-teamcity-logger",
    sbtPlugin := true,

    pluginCrossBuildSettings,
    pluginPublishingSettings,

    // Library dependency to be able to use Java API for `##teamcity` service messages
    libraryDependencies ++= Seq(
      ("org.jetbrains.teamcity" % "serviceMessages" % "2021.1")
        .exclude("org.jetbrains.teamcity.idea", "annotations")
    ),
    // needed for "service messages" library
    resolvers += "jetbrains-teamcity-repository" at "https://download.jetbrains.com/teamcity-repository",
  )

val isSbt1 = settingKey[Boolean]("Whether the current cross-build target is SBT 1.x.")
val isSbt2 = settingKey[Boolean]("Whether the current cross-build target is SBT 2.x.")
def isSbt1Impl(version: String): Boolean = version.startsWith("1.")
def isSbt2Impl(version: String): Boolean = version.startsWith("2.")

lazy val pluginCrossBuildSettings: Seq[Def.Setting[_]] = Seq(
  scalaVersion := ScalaVersion_212,
  crossScalaVersions := Seq(
    ScalaVersion_212, // for sbt 1.x
    ScalaVersion_3 // for sbt 2.x
  ),
  pluginCrossBuild / sbtVersion := {
    scalaBinaryVersion.value match {
      case "2.12" => SbtVersion_1xx
      case "3" => SbtVersion_2xx
      case binaryVersion => sys.error(s"Unsupported Scala binary version for sbt-teamcity-logger: $binaryVersion")
    }
  },
  isSbt1 := isSbt1Impl((pluginCrossBuild / sbtVersion).value),
  isSbt2 := isSbt2Impl((pluginCrossBuild / sbtVersion).value),
  // SBT 1 plugins must still load in Java 8 runtimes. The SBT 2 cross-build
  // needs Java 17, so keep its default release target.
  Compile / scalacOptions ++= {
    if (isSbt1.value) Seq("-release", "8")
    else Nil
  },
  Compile / javacOptions ++= {
    if (isSbt1.value) Seq("--release", "8")
    else Nil
  },
) ++ sbt2CompatibilitySettings

/**
 * Configures the Scala 3/SBT 2 target while this project is still hosted by SBT 1.
 *
 * The Scala 2.12/SBT 1 target remains a normal `sbtPlugin`. Its conventions provide the SBT API dependency and
 * discover `src/main/scala-sbt-1.0` automatically. SBT 1.12 cannot enable those conventions for an SBT 2 target,
 * so the SBT 2 target supplies the API and source directory explicitly. It nevertheless uses SBT 2's standard
 * `_sbt2_3` Maven coordinate via `CrossVersion.binaryWith`.
 *
 * Re-add the SBT 2 API as `Provided` and its source directory explicitly. Keeping the SBT 1 settings implicit avoids
 * duplicating its convention-supplied dependency and source-directory configuration.
 *
 * `sbtPlugin` remains enabled for both targets so each published artifact contains plugin-discovery metadata.
 * TODO: After moving the host in project/build.properties to SBT 2, remove this compatibility shim (the explicit
 * SBT API, source directory, and cross-version setting).
 */
lazy val sbt2CompatibilitySettings: Seq[Def.Setting[_]] = Seq(
  crossVersion := {
    if (isSbt2.value) CrossVersion.binaryWith("sbt2_", "")
    else CrossVersion.binary
  },
  libraryDependencies ++= {
    if (isSbt2.value)
      Seq((pluginCrossBuild / sbtDependency).value % Provided)
    else Nil
  },
  Compile / unmanagedSourceDirectories ++= {
    if (isSbt2.value) Seq(file("src/main/scala-sbt-2.0"))
    else Nil
  },
)

lazy val pluginPublishingSettings: Seq[Def.Setting[_]] = Seq(
  organization := "org.jetbrains.teamcity.plugins.sbt",
  versionScheme := Some("early-semver"),
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  publishMavenStyle := true,
  sbtPluginPublishLegacyMavenStyle := false,
  Compile / packageBin := (Compile / assembly).value,
  // Avoid feeding packageBin back into assembly's classpath: packageBin is the
  // assembly task for this project, so exported classes must remain directories.
  Compile / exportJars := false,
  // The helper integration-test project is aggregated for compilation but does not
  // produce a plugin jar. Keep packageBin focused on the logger artifact.
  Compile / packageBin / aggregate := false,
  assembly / aggregate := false,
  // Assembly runs its scoped test task by default. The integration test harness is
  // exercised explicitly by testSbt100/testSbt200; do not run it as a packaging
  // dependency.
  assembly / test := sbt.protocol.testing.TestResult.Passed,
  // TODO: Modernize deprecated sbt API usage in logger sources in a focused follow-up.
  // TeamCity selects the compatible JAR by its sbt-distrib/<sbt-line> directory, so both direct-load files use this
  // stable filename. Maven publication derives its version-qualified filename from the project coordinate instead.
  assembly / assemblyJarName := "sbt-teamcity-logger.jar",
  assembly / assemblyOutputPath := {
    val scala = scalaBinaryVersion.value
    val sbt = (pluginCrossBuild / sbtBinaryVersion).value
    baseDirectory.value / "target" / s"scala-$scala" / s"sbt-$sbt" / "sbt-teamcity-logger.jar"
  },
  Test / publishArtifact := false,
)

/**
 * Scala/JUnit integration-test harness for the TeamCity logger plugin.
 *
 * Integration tests load the assembled plugin jar into nested sbt runs with sbt's `apply -cp` command.
 */
lazy val integrationTests: Project = (project in file("test"))
  .settings(
    // Keep this helper project private and independent of published plugin cross-builds.
    name := "sbt-tc-logger-integration-tests",
    publish / skip := true,

    scalaVersion := "3.8.4",
    scalacOptions += "-no-indent",
    crossScalaVersions := Seq(scalaVersion.value),

    // Fork so the harness can launch nested sbt processes.
    Test / fork := true,
    // Nested sbt processes share an isolated global base, so run cases sequentially.
    Test / parallelExecution := false,

    libraryDependencies ++= junitTestFrameworkDependencies,
  )

// JUnit runs the outer harness; the single current sbt launcher boots fixture-selected sbt versions.
lazy val junitTestFrameworkDependencies: Seq[ModuleID] = Seq(
  "junit" % "junit" % "4.13.2" % Test,
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
)

addCommandAlias("testSbt100", "integrationTests / testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutputTest100")
addCommandAlias("testSbt200", "integrationTests / testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutputTest200")
