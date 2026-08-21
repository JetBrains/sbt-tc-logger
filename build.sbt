import sbt.Def
import sbtassembly.AssemblyPlugin.autoImport.*

// Compile the SBT 1.4+ artifact with the latest Scala 2.12 compiler. The two
// plugin targets are intentionally concrete SBT projects so IntelliJ exposes
// both target classpaths at the same time. See CONTRIBUTING.md#cross-building-topology.
val ScalaVersion_212 = "2.12.21"
val ScalaVersion_3 = "3.8.4"

// SBT 1.4 introduced the native appender API used by the TeamCity logger. Older SBT 1.x releases remain on the
// previously published logger artifact; this artifact intentionally supports SBT 1.4+ only.
val SbtVersion_1xx = "1.4.0"
val SbtVersion_2xx = "2.0.0"

val WorkspaceRoot = file(".").getCanonicalFile
val SharedLoggerSources = WorkspaceRoot / "src" / "main" / "scala"
val SharedLoggerTestSources = WorkspaceRoot / "src" / "test" / "scala"
val IntegrationTestArtifactsDirectory = WorkspaceRoot / "target" / "integration-tests" / "artifacts"

ThisBuild / resolvers := Seq(
  "JetBrains Maven Central" at "https://cache-redirector.jetbrains.com/maven-central"
)

val prepareIntegrationTestArtifacts = taskKey[File](
  "Assembles and stages logger JARs for integration tests."
)

lazy val root: Project = (project in file("."))
  .aggregate(
    loggerSbt1,
    loggerSbt2,
    integrationTestsFramework,
    integrationTests
  )
  .settings(
    name := "sbt-teamcity-logger",
    publish / skip := true,
    Compile / unmanagedSourceDirectories := Nil,
    // `src/test/scala` belongs to the two concrete plugin targets. Keeping it
    // off this aggregate lets IntelliJ import one shared test-sources module
    // with a dependency from each target-specific test module; see
    // CONTRIBUTING.md#cross-building-topology for why this is intentional.
    Test / unmanagedSourceDirectories := Nil,
    Test / managedSourceDirectories := Nil,
    Test / unmanagedResourceDirectories := Nil,
    Test / managedResourceDirectories := Nil,
    // Aggregation invokes the concrete modules' staging tasks. The root result
    // is their shared destination, which is useful for manual invocation only.
    prepareIntegrationTestArtifacts := {
      IO.createDirectory(IntegrationTestArtifactsDirectory)
      IntegrationTestArtifactsDirectory
    },
    prepareIntegrationTestArtifacts / aggregate := true,
  )

lazy val loggerSbt1: Project = (project in file("src/main/scala-sbt-1.0"))
  .settings(
    loggerProjectSettings("loggerSbt1"),
    loggerSbt1Settings,
  )

/**
 * Fixed SBT 2/Scala 3 logger target while this repository is hosted by SBT 1.
 *
 * SBT 1.12 cannot provide the SBT 2 plugin conventions, so this module keeps
 * the explicit SBT API dependency and the standard SBT 2 Maven cross-version.
 */
lazy val loggerSbt2: Project = (project in file("src/main/scala-sbt-2.0"))
  .settings(
    loggerProjectSettings("loggerSbt2"),
    loggerSbt2Settings,
  )

/** Settings shared by both concrete logger modules. */
def loggerProjectSettings(targetDirectory: String): Seq[Def.Setting[_]] = Seq(
  name := "sbt-teamcity-logger",
  sbtPlugin := true,
  // Each project is rooted at its SBT-specific source directory. Attach the
  // common implementation explicitly so the IDE imports it into both modules.
  Compile / unmanagedSourceDirectories := Seq(baseDirectory.value, SharedLoggerSources),
  // Unit tests are source-compatible across the supported SBT lines. Attach
  // the one shared root to both targets rather than making the aggregate root
  // own it; IntelliJ then mirrors the shared-main-sources topology for tests.
  // See CONTRIBUTING.md#cross-building-topology for the trade-off.
  Test / unmanagedSourceDirectories := Seq(SharedLoggerTestSources),
  Test / unmanagedResourceDirectories := Nil,
  // Do not create target directories below source roots; each target is also
  // isolated so both modules can package concurrently.
  target := WorkspaceRoot / "target" / targetDirectory,

  // Library dependency to be able to use Java API for `##teamcity` service messages
  libraryDependencies ++= Seq(
    "org.jetbrains.teamcity" % "serviceMessages" % "2026.1.3",
    "junit" % "junit" % "4.13.2" % Test,
    "com.github.sbt" % "junit-interface" % "0.13.3" % Test
  ),
  // needed for "service messages" library
  resolvers += "jetbrains-teamcity-repository" at "https://download.jetbrains.com/teamcity-repository",
) ++ pluginPublishingSettings ++ integrationTestArtifactPreparationSettings

/** Fixed SBT 1.4+/Scala 2.12 logger target. */
lazy val loggerSbt1Settings: Seq[Def.Setting[_]] = Seq(
  scalaVersion := ScalaVersion_212,
  crossScalaVersions := Nil,
  pluginCrossBuild / sbtVersion := SbtVersion_1xx,
  // SBT 1 plugins must still load in Java 8 runtimes.
  Compile / scalacOptions ++= Seq("-release", "8"),
  Compile / javacOptions ++= Seq("--release", "8"),
)

lazy val loggerSbt2Settings: Seq[Def.Setting[_]] = Seq(
  scalaVersion := ScalaVersion_3,
  crossScalaVersions := Nil,
  pluginCrossBuild / sbtVersion := SbtVersion_2xx,
  crossVersion := CrossVersion.binaryWith("sbt2_", ""),
  libraryDependencies += (pluginCrossBuild / sbtDependency).value % Provided,
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

lazy val integrationTestArtifactPreparationSettings: Seq[Def.Setting[_]] = Seq(
  prepareIntegrationTestArtifacts := {
    val packagedJar = (Compile / packageBin).value
    // Test-artifact contract: one staged JAR per sbt plugin binary version. `SbtPluginUnderTest.packagedJar`
    // resolves this same path, so tests never depend on `packageBin`'s versioned output layout or filename.
    val sbtPluginBinaryVersion = (pluginCrossBuild / sbtBinaryVersion).value
    val stagedJar = IntegrationTestArtifactsDirectory / s"sbt-$sbtPluginBinaryVersion.jar"
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
 * Shared Scala/JUnit utilities used by the logger integration-test harness.
 *
 * Its sources intentionally live under `integration-tests-framework/src` and
 * are compiled only in the Test configuration.
 */
lazy val integrationTestsFramework: Project = (project in file("integration-tests-framework"))
  .settings(
    testOnlyModuleSettings,
    name := "sbt-tc-logger-integration-tests-framework",
    libraryDependencies ++= junitTestFrameworkDependencies,
  )

/**
 * Scala/JUnit integration-test harness for the TeamCity logger plugin.
 *
 * Integration tests load the assembled plugin jar into nested sbt runs with sbt's `apply -cp` command.
 * Its sources intentionally live under `integration-tests/src`, alongside `testData` fixtures.
 */
lazy val integrationTests: Project = (project in file("integration-tests"))
  .dependsOn(integrationTestsFramework % "test->test")
  .settings(
    testOnlyModuleSettings,
    name := "sbt-tc-logger-integration-tests",
    // Fork so the harness can launch nested sbt processes.
    Test / fork := true,
    // Nested sbt processes share an isolated global base, so run cases sequentially.
    Test / parallelExecution := false,
    // Every standard or focused test invocation receives fresh staged plugin
    // artifacts without needing a preceding `+prepare...` command.
    Test / test := (Test / test)
      .dependsOn(
        loggerSbt1 / prepareIntegrationTestArtifacts,
        loggerSbt2 / prepareIntegrationTestArtifacts
      )
      .value,
    Test / testOnly := (Test / testOnly)
      .dependsOn(
        loggerSbt1 / prepareIntegrationTestArtifacts,
        loggerSbt2 / prepareIntegrationTestArtifacts
      )
      .evaluated,

    libraryDependencies ++= junitTestFrameworkDependencies ++ Seq(
      // Exact transcripts are validated as raw wire text, but every TeamCity-looking line must still be syntactically valid.
      "org.jetbrains.teamcity" % "serviceMessages" % "2026.1.3" % Test,
    ),
    resolvers += "jetbrains-teamcity-repository" at "https://download.jetbrains.com/teamcity-repository",
  )

/**
 * A test-only module has exactly one source root: `<module>/src`.
 * Keep conventional main and test source roots disabled so IntelliJ imports
 * the same model as the SBT build.
 */
lazy val testOnlyModuleSettings: Seq[Def.Setting[_]] = Seq(
  publish / skip := true,
  scalaVersion := ScalaVersion_3,
  scalacOptions += "-no-indent",
  Compile / unmanagedSourceDirectories := Nil,
  Compile / managedSourceDirectories := Nil,
  Test / unmanagedSourceDirectories := Seq(baseDirectory.value / "src"),
  Test / managedSourceDirectories := Nil,
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
// JUnit's category filter keeps every non-matrix integration test in the shared auxiliary bucket.
addCommandAlias("testOther", ";project integrationTests;testOnly -- --exclude-categories=jetbrains.buildServer.sbtlogger.SbtRuntimeMatrix")

// Candidate commands are deliberately target-only. Reviewing and copying a candidate into testdata is a separate step.
addCommandAlias("generateSbt1_4_Jdk8OutputCandidates", ";set integrationTests / Test / javaOptions += \"-Dsbt.logger.transcripts.candidate=true\";project integrationTests;testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutput_TestSbt1_4_Jdk8;session clear")
addCommandAlias("generateSbt1_12_Jdk8OutputCandidates", ";set integrationTests / Test / javaOptions += \"-Dsbt.logger.transcripts.candidate=true\";project integrationTests;testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutput_TestSbt1_12_Jdk8;session clear")
addCommandAlias("generateSbt1_12_Jdk17OutputCandidates", ";set integrationTests / Test / javaOptions += \"-Dsbt.logger.transcripts.candidate=true\";project integrationTests;testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutput_TestSbt1_12_Jdk17 jetbrains.buildServer.sbtlogger.SbtDetailedDependencyResolution_TestSbt1_12_Jdk17;session clear")
addCommandAlias("generateSbt2_0_Jdk17OutputCandidates", ";set integrationTests / Test / javaOptions += \"-Dsbt.logger.transcripts.candidate=true\";project integrationTests;testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutput_TestSbt2_0_Jdk17 jetbrains.buildServer.sbtlogger.SbtDetailedDependencyResolution_TestSbt2_0_Jdk17;session clear")
