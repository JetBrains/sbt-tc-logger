import sbt.Def
import sbtassembly.AssemblyPlugin.autoImport.*

// Compile the SBT 1.4+ artifact with the latest Scala 2.12 compiler. Plugins cross-build on the Scala binary
// version, while the integration suite proves the 1.4/JDK 8 minimum and a current SBT 1.12 runtime.
val ScalaVersion_212 = "2.12.21"
val ScalaVersion_3 = "3.8.4"

// SBT 1.4 introduced the native appender API used by the TeamCity logger. Older SBT 1.x releases remain on the
// previously published logger artifact; this artifact intentionally supports SBT 1.4+ only.
val SbtVersion_1xx = "1.4.0"
val SbtVersion_2xx = "2.0.0"

ThisBuild / resolvers := Seq(
  "JetBrains Maven Central" at "https://cache-redirector.jetbrains.com/maven-central"
)

lazy val logger: Project = (project in file("."))
  .aggregate(
    integrationTests
  )
  .settings(
    name := "sbt-teamcity-logger",
    sbtPlugin := true,

    pluginCrossBuildSettings,
    pluginPublishingSettings,
    integrationTestArtifactPreparationSettings,

    // Library dependency to be able to use Java API for `##teamcity` service messages
    libraryDependencies ++= Seq(
      "org.jetbrains.teamcity" % "serviceMessages" % "2026.1.3",
      "junit" % "junit" % "4.13.2" % Test,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test
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
  // Details:
  //  1. `packageBin` normally creates a thin JAR containing only this project's classes and resources
  //  2. `assembly` also embeds runtime dependencies in the JAR
  //  3. `publish` publishes the file returned by `packageBin`.
  // TeamCity direct-loads that one published JAR; without this override, serviceMessages would be absent at runtime.
  Compile / packageBin := (Compile / assembly).value,
  // Tests run in a dedicated build configuration, so do not rerun them while assembling or publishing.
  assembly / test := sbt.protocol.testing.TestResult.Passed,
)

val prepareIntegrationTestArtifacts = taskKey[File](
  "Assembles and stages a logger JAR for integration tests."
)

lazy val integrationTestArtifactPreparationSettings: Seq[Def.Setting[_]] = Seq(
  prepareIntegrationTestArtifacts := {
    val packagedJar = (Compile / packageBin).value
    // Test-artifact contract: one staged JAR per sbt plugin binary version. `SbtPluginUnderTest.packagedJar`
    // resolves this same path, so tests never depend on `packageBin`'s versioned output layout or filename.
    val sbtPluginBinaryVersion = (pluginCrossBuild / sbtBinaryVersion).value
    val stagedJar = target.value / "integration-tests" / "artifacts" / s"sbt-$sbtPluginBinaryVersion.jar"
    val log = streams.value.log

    IO.createDirectory(stagedJar.getParentFile)
    val requiresStaging = !stagedJar.isFile ||
      java.nio.file.Files.mismatch(packagedJar.toPath, stagedJar.toPath) != -1L

    if (requiresStaging) {
      IO.copyFile(packagedJar, stagedJar, preserveLastModified = true)
      log.info(s"Staged integration-test logger JAR: ${stagedJar.getAbsolutePath}")
    } else {
      log.info(s"Integration-test logger JAR is up-to-date: ${stagedJar.getAbsolutePath}")
    }
    stagedJar
  },
  prepareIntegrationTestArtifacts / aggregate := false,
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

// JUnit runs the outer harness; the current launcher for each supported SBT line boots fixture-selected sbt versions.
lazy val junitTestFrameworkDependencies: Seq[ModuleID] = Seq(
  "junit" % "junit" % "4.13.2" % Test,
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
)

addCommandAlias("testSbt1_4_Jdk8", ";project integrationTests;testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutput_TestSbt1_4_Jdk8")
addCommandAlias("testSbt1_12_Jdk8", ";project integrationTests;testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutput_TestSbt1_12_Jdk8")
addCommandAlias("testSbt1_12_Jdk17", ";project integrationTests;testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutput_TestSbt1_12_Jdk17 jetbrains.buildServer.sbtlogger.SbtDetailedDependencyResolution_TestSbt1_12_Jdk17")
addCommandAlias("testSbt2_0_Jdk17", ";project integrationTests;testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutput_TestSbt2_0_Jdk17 jetbrains.buildServer.sbtlogger.SbtDetailedDependencyResolution_TestSbt2_0_Jdk17")
