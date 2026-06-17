import sbtassembly.AssemblyPlugin.autoImport._

ThisBuild / organization := "org.jetbrains.teamcity.plugins"
ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))

lazy val root: Project = (project in file("."))
  .aggregate(
    logger,
    integrationTests
  )
  .settings(
    name := "sbt-tc-logger-root",
    publish / skip := true
  )

lazy val logger: Project = (project in file("logger"))
  .settings(loggerSettings: _*)

// These share logger sources and expose cross-built assemblies as ordinary task dependencies.
lazy val loggerArtifactSbt013: Project = loggerArtifactProject(sbt013LoggerArtifact)
lazy val loggerArtifactSbt1: Project = loggerArtifactProject(sbt1LoggerArtifact)

lazy val sbt013LoggerArtifact = LoggerArtifactBuild("sbt 0.13", "loggerArtifactSbt013", "0.13.17", "2.10.7", "sbt-0.13-artifact", "0.13")
lazy val sbt1LoggerArtifact = LoggerArtifactBuild("sbt 1.x", "loggerArtifactSbt1", "1.12.12", "2.12.21", "sbt-1-artifact", "1.0")

lazy val loggerSettings = Seq(
  sbtPlugin := true,
  name := "sbt-teamcity-logger",
  crossSbtVersions := Seq("0.13.17", "1.12.12"),
  unmanagedBase := baseDirectory.value / "lib",
  Test / publishArtifact := false,
  publishMavenStyle := false,
  pomExtra :=
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        <distribution>repo</distribution>
      </license>
    </licenses>,
  Compile / assembly / artifact := {
    val art = (Compile / assembly / artifact).value
    art.withClassifier(Some("assembly"))
  },
  addArtifact(Compile / assembly / artifact, Compile / assembly),
  assembly / assemblyJarName := "sbt-teamcity-logger.jar"
)

def loggerArtifactProject(artifact: LoggerArtifactBuild): Project =
  Project(artifact.projectId, file("logger"))
    .settings(loggerSettings: _*)
    .settings(
      pluginCrossBuild / sbtVersion := artifact.sbtVersion,
      scalaVersion := artifact.scalaVersion,
      target := baseDirectory.value / "target" / artifact.buildTargetDirectory,
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
lazy val integrationTestSbt1Launcher = taskKey[File]("Resolved sbt 1.0 launcher jar used by sbt 1.x integration tests.")

lazy val repoRoot = file(".").getAbsoluteFile
lazy val integrationTestPluginBase = repoRoot / "target" / "integration-test-artifacts" / "tc_plugin"
lazy val integrationTestPluginJarName = "sbt-teamcity-logger.jar"
def integrationTestPluginJar(artifact: LoggerArtifactBuild): File =
  integrationTestPluginBase / artifact.stagingDirectory / integrationTestPluginJarName
lazy val integrationTestSbt013PluginJar = integrationTestPluginJar(sbt013LoggerArtifact)
lazy val integrationTestSbt1PluginJar = integrationTestPluginJar(sbt1LoggerArtifact)

/**
 * Hidden configurations used only to resolve launcher jars for the nested sbt processes.
 *
 * They keep sbt-launch out of the JUnit harness classpath while still letting
 * `update.value.select(...)` find the exact launcher files to pass to SbtProcess.
 */
lazy val Sbt013Launcher = config("sbt013Launcher").hide
lazy val Sbt1Launcher = config("sbt1Launcher").hide

Global / prepareIntegrationTestArtifacts := {
  val artifacts = Seq(
    (sbt013LoggerArtifact, (loggerArtifactSbt013 / Compile / assembly).value),
    (sbt1LoggerArtifact, (loggerArtifactSbt1 / Compile / assembly).value)
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
 * Java JUnit integration-test harness for the TeamCity logger plugin.
 *
 * Workflow for `sbt test`:
 *
 * 1. The root project aggregates this project, so root `test` runs `integrationTests / Test / test`.
 * 2. `integrationTests / Test / test` depends on `prepareIntegrationTestArtifacts`, which builds
 *    both helper assemblies and stages them under
 *    `target/integration-test-artifacts/tc_plugin/{0.13,1.0}/sbt-teamcity-logger.jar`.
 * 3. This project resolves sbt-launch jars as managed dependencies in hidden configurations.
 * 4. Forked JUnit receives the repository root, both launcher paths, and both plugin jar paths
 *    through `sbt.tc.*` system properties.
 * 5. JUnit classes in `test/src` call `SbtProcess`, which selects sbt 0.13 or sbt 1.x from the
 *    fixture path, starts a fresh nested sbt JVM with Java 8, applies the staged plugin jar, and
 *    runs the requested fixture command.
 * 6. Nested sbt stdout is matched against regex lines in each fixture's `output.txt` files, while
 *    optional `excludes.txt` files assert that unwanted service messages were not emitted.
 *
 * The outer build may run on a modern JDK and sbt 1.12.12. The nested sbt JVMs intentionally use
 * Java 8 because legacy sbt 0.13 fixtures require it.
 */
lazy val integrationTests: Project = (project in file("test"))
  .configs(Sbt013Launcher, Sbt1Launcher)
  .settings(
    inConfig(Sbt013Launcher)(Defaults.configSettings),
    inConfig(Sbt1Launcher)(Defaults.configSettings),
    name := "sbt-tc-logger-integration-tests",
    publish / skip := true,
    autoScalaLibrary := false,
    crossPaths := false,
    resolvers += Resolver.url(
      "sbt-ivy-releases",
      url("https://repo.typesafe.com/typesafe/ivy-releases")
    )(Resolver.ivyStylePatterns),
    Test / javaSource := baseDirectory.value / "src",
    Test / unmanagedSourceDirectories := Seq((Test / javaSource).value),
    Test / fork := true,
    Test / parallelExecution := false,
    Test / javaOptions ++= {
      val repoRoot = (LocalRootProject / baseDirectory).value
      val launcher013 = integrationTestSbt013Launcher.value
      val launcher1 = integrationTestSbt1Launcher.value
      Seq(
        s"-Dsbt.tc.repo.root=${repoRoot.getAbsolutePath}",
        s"-Dsbt.tc.sbt.launcher.013=${launcher013.getAbsolutePath}",
        s"-Dsbt.tc.sbt.launcher.1=${launcher1.getAbsolutePath}",
        s"-Dsbt.tc.plugin.013=${integrationTestSbt013PluginJar.getAbsolutePath}",
        s"-Dsbt.tc.plugin.1=${integrationTestSbt1PluginJar.getAbsolutePath}"
      )
    },
    Test / test := (Test / test).dependsOn(Global / prepareIntegrationTestArtifacts).value,
    integrationTestSbt013Launcher := {
      val launchers = update.value.select(configurationFilter(Sbt013Launcher.name), moduleFilter(name = "sbt-launch"), artifactFilter(name = "sbt-launch"))
      launchers.headOption.getOrElse(sys.error("Could not resolve org.scala-sbt:sbt-launch for integration tests."))
    },
    integrationTestSbt1Launcher := {
      val launchers = update.value.select(configurationFilter(Sbt1Launcher.name), moduleFilter(name = "sbt-launch"), artifactFilter(name = "sbt-launch"))
      launchers.headOption.getOrElse(sys.error("Could not resolve org.scala-sbt:sbt-launch for integration tests."))
    },
    libraryDependencies ++= Seq(
      "junit" % "junit" % "4.13.2" % Test,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "org.scala-sbt" % "sbt-launch" % "0.13.17" % Sbt013Launcher.name,
      "org.scala-sbt" % "sbt-launch" % "1.0.0" % Sbt1Launcher.name
    )
  )


addCommandAlias("testSbt013", "; prepareIntegrationTestArtifacts; integrationTests / testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutputTest")
addCommandAlias("testSbt1", "; prepareIntegrationTestArtifacts; integrationTests / testOnly jetbrains.buildServer.sbtlogger.SbtLoggerOutputTest_1_0")
