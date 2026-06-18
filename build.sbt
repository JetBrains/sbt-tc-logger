import sbtassembly.AssemblyPlugin.autoImport._

ThisBuild / organization := "org.jetbrains.teamcity.plugins.sbt"
ThisBuild / scalaVersion := "2.12.21"
ThisBuild / crossScalaVersions := Seq("2.10.7", "2.12.21")
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))
ThisBuild / resolvers += "jetbrains-teamcity-repository" at "https://download.jetbrains.com/teamcity-repository"

lazy val repoRoot = file(".").getAbsoluteFile

lazy val logger: Project = (project in file("."))
  .aggregate(
    integrationTests
  )
  .settings(loggerSettings: _*)

// Private, non-published projects used only to give integration tests concrete runtime jar tasks.
lazy val loggerStagingSbt013: Project = integrationTestLoggerStagingProject(sbt013LoggerArtifact)
lazy val loggerStagingSbt100: Project = integrationTestLoggerStagingProject(sbt100LoggerArtifact)

lazy val sbt013LoggerArtifact = LoggerArtifactBuild("sbt 0.13", "013", "loggerStagingSbt013", "0.13.17", "2.10.7", "sbt-0.13-artifact", "0.13")
lazy val sbt100LoggerArtifact = LoggerArtifactBuild("sbt 1.x", "100", "loggerStagingSbt100", "1.12.12", "2.12.21", "sbt-1-artifact", "1.0")

lazy val loggerSettings = Seq(
  sbtPlugin := true,
  name := "sbt-teamcity-logger",
  crossSbtVersions := Nil,
  pluginCrossBuild / sbtVersion := {
    scalaBinaryVersion.value match {
      case "2.10" => "0.13.17"
      case "2.12" => "1.12.12"
      case binaryVersion => sys.error(s"Unsupported Scala binary version for sbt-teamcity-logger: $binaryVersion")
    }
  },
  libraryDependencies ++= Seq(
    ("org.jetbrains.teamcity" % "serviceMessages" % "2021.1")
      .exclude("org.jetbrains.teamcity.idea", "annotations")
  ),
  Test / publishArtifact := false,
  publishMavenStyle := true,
  sbtPluginPublishLegacyMavenStyle := false,
  Compile / packageBin := (Compile / assembly).value,
  // TODO: Modernize deprecated sbt API usage in logger sources in a focused follow-up.
  assembly / assemblyJarName := "sbt-teamcity-logger.jar"
)

def integrationTestLoggerStagingProject(artifact: LoggerArtifactBuild): Project =
  Project(artifact.projectId, repoRoot / "target" / artifact.buildTargetDirectory / "project-base")
    .settings(loggerSettings: _*)
    .settings(
      Compile / sourceDirectory := repoRoot / "src" / "main",
      pluginCrossBuild / sbtVersion := artifact.sbtVersion,
      scalaVersion := artifact.scalaVersion,
      target := repoRoot / "target" / artifact.buildTargetDirectory,
      publish / skip := true
    )

/**
 * Builds and stages the local plugin jars used by nested integration-test sbt processes.
 *
 * The JUnit harness does not publish the plugin into an Ivy repository. Instead, each nested
 * sbt process receives an `apply -cp <jar> jetbrains.buildServer.sbtlogger.SbtTeamCityLogger`
 * command. This task prepares those jars up front so the harness can pass stable filesystem
 * paths via system properties.
 */
lazy val prepareIntegrationTestArtifacts = taskKey[Seq[File]]("Build and copy sbt-teamcity-logger jars used by integration tests.")

/**
 * Resolves the sbt launcher jar used for fixture projects under test/testdata.
 *
 * Launcher jars are managed dependencies of the integration-test project, which replaces the
 * old Ant download step and keeps the whole test setup inside sbt's dependency graph.
 */
lazy val integrationTestSbt013Launcher = taskKey[File]("Resolved sbt 0.13 launcher jar used by legacy integration tests.")
lazy val integrationTestSbt100Launcher = taskKey[File]("Resolved sbt 1.0 launcher jar used by sbt 1.x integration tests.")

lazy val integrationTestPluginBase = repoRoot / "target" / "integration-test-artifacts" / "tc_plugin"
lazy val integrationTestPluginJarName = "sbt-teamcity-logger.jar"
def integrationTestPluginJar(artifact: LoggerArtifactBuild): File =
  integrationTestPluginBase / artifact.stagingDirectory / integrationTestPluginJarName
lazy val integrationTestSbt013PluginJar = integrationTestPluginJar(sbt013LoggerArtifact)
lazy val integrationTestSbt100PluginJar = integrationTestPluginJar(sbt100LoggerArtifact)

/**
 * Hidden configurations used only to resolve launcher jars for the nested sbt processes.
 *
 * They keep sbt-launch out of the JUnit harness classpath while still letting
 * `update.value.select(...)` find the exact launcher files for forked integration tests.
 */
lazy val Sbt013Launcher = config("sbt013Launcher").hide
lazy val Sbt100Launcher = config("sbt100Launcher").hide

Global / prepareIntegrationTestArtifacts := {
  val artifacts = Seq(
    (sbt013LoggerArtifact, (loggerStagingSbt013 / Compile / packageBin).value),
    (sbt100LoggerArtifact, (loggerStagingSbt100 / Compile / packageBin).value)
  )

  artifacts.map { case (artifact, source) =>
    val target = integrationTestPluginJar(artifact)
    IO.createDirectory(target.getParentFile)
    IO.copyFile(source, target)
    streams.value.log.info(s"Copied ${artifact.displayName} logger to ${target.getAbsolutePath}")
    target
  }
}

/**
 * Scala/JUnit integration-test harness for the TeamCity logger plugin.
 *
 * The build prepares plugin jars and sbt launchers, then passes their paths to
 * the forked Scala harness via `sbt.tc.*` system properties. Runtime selection,
 * fixture cases, and output matching live in `test/src/test/scala`; see `README.md`
 * for the full test workflow.
 */
lazy val integrationTests: Project = (project in file("test"))
  .configs(Sbt013Launcher, Sbt100Launcher)
  .settings(
    // Give the nested sbt launchers isolated dependency buckets.
    inConfig(Sbt013Launcher)(Defaults.configSettings),
    inConfig(Sbt100Launcher)(Defaults.configSettings),

    // Keep this helper project private and independent from published plugin cross-builds.
    name := "sbt-tc-logger-integration-tests",
    publish / skip := true,
    scalaVersion := "3.8.4",
    crossScalaVersions := Seq(scalaVersion.value),
    Test / scalacOptions += "-no-indent",

    // Resolve legacy sbt launcher artifacts from the ivy-style sbt repository.
    resolvers += Resolver.url(
      "sbt-ivy-releases",
      url("https://repo.typesafe.com/typesafe/ivy-releases")
    )(Resolver.ivyStylePatterns),

    // Fork so the harness receives a controlled set of sbt.tc.* system properties.
    Test / fork := true,

    // Nested sbt processes share fixture/global directories, so run cases sequentially.
    Test / parallelExecution := false,

    // Tell the forked harness where the repo, launchers, and staged plugin jars are.
    Test / javaOptions ++= {
      val repoRoot = (LocalRootProject / baseDirectory).value
      val launcher013 = integrationTestSbt013Launcher.value
      val launcher100 = integrationTestSbt100Launcher.value
      Seq(
        s"-Dsbt.tc.repo.root=${repoRoot.getAbsolutePath}",
        s"-Dsbt.tc.sbt.launcher.${sbt013LoggerArtifact.versionSuffix}=${launcher013.getAbsolutePath}",
        s"-Dsbt.tc.sbt.launcher.${sbt100LoggerArtifact.versionSuffix}=${launcher100.getAbsolutePath}",
        s"-Dsbt.tc.plugin.${sbt013LoggerArtifact.versionSuffix}=${integrationTestSbt013PluginJar.getAbsolutePath}",
        s"-Dsbt.tc.plugin.${sbt100LoggerArtifact.versionSuffix}=${integrationTestSbt100PluginJar.getAbsolutePath}"
      )
    },

    // Build and stage local plugin jars before full-suite and targeted test runs.
    Test / test := (Test / test).dependsOn(Global / prepareIntegrationTestArtifacts).value,
    Test / testOnly := (Test / testOnly).dependsOn(Global / prepareIntegrationTestArtifacts).evaluated,

    // Select the resolved launcher jars that nested sbt processes will execute.
    integrationTestSbt013Launcher := {
      val launchers = update.value.select(configurationFilter(Sbt013Launcher.name), moduleFilter(name = "sbt-launch"), artifactFilter(name = "sbt-launch"))
      launchers.headOption.getOrElse(sys.error("Could not resolve org.scala-sbt:sbt-launch for integration tests."))
    },
    integrationTestSbt100Launcher := {
      val launchers = update.value.select(configurationFilter(Sbt100Launcher.name), moduleFilter(name = "sbt-launch"), artifactFilter(name = "sbt-launch"))
      launchers.headOption.getOrElse(sys.error("Could not resolve org.scala-sbt:sbt-launch for integration tests."))
    },

    // JUnit runs the outer harness; sbt-launch jars are inputs for nested processes only.
    libraryDependencies ++= Seq(
      "junit" % "junit" % "4.13.2" % Test,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "org.scala-sbt" % "sbt-launch" % "0.13.17" % Sbt013Launcher.name,
      "org.scala-sbt" % "sbt-launch" % "1.0.0" % Sbt100Launcher.name
    )
  )


addCommandAlias("testSbt013", "integrationTests / testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutputTest013")
addCommandAlias("testSbt100", "integrationTests / testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutputTest100")
