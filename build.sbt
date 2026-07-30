import sbt.Def
import sbtassembly.AssemblyPlugin.autoImport.*

val ScalaVersion_212 = "2.12.21"

val SbtVersion_1xx = "1.12.12"

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

lazy val pluginCrossBuildSettings: Seq[Def.Setting[_]] = Seq(
  scalaVersion := ScalaVersion_212,
  crossScalaVersions := Seq(
    ScalaVersion_212 // for sbt 1.x
  ),
  crossSbtVersions := Nil,
  pluginCrossBuild / sbtVersion := {
    scalaBinaryVersion.value match {
      case "2.12" => SbtVersion_1xx
      case binaryVersion => sys.error(s"Unsupported Scala binary version for sbt-teamcity-logger: $binaryVersion")
    }
  },
)

lazy val pluginPublishingSettings: Seq[Def.Setting[_]] = Seq(
  organization := "org.jetbrains.teamcity.plugins.sbt",
  versionScheme := Some("early-semver"),
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  publishMavenStyle := true,
  sbtPluginPublishLegacyMavenStyle := false,
  Compile / packageBin := (Compile / assembly).value,
  // TODO: Modernize deprecated sbt API usage in logger sources in a focused follow-up.
  assembly / assemblyJarName := "sbt-teamcity-logger.jar",
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
