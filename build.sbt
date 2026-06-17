import sbtassembly.AssemblyPlugin.autoImport._

ThisBuild / organization := "org.jetbrains.teamcity.plugins"
ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))

lazy val root = (project in file("."))
  .aggregate(logger)
  .settings(
    name := "sbt-tc-logger-root",
    publish / skip := true
  )

lazy val logger = (project in file("logger"))
  .settings(loggerSettings: _*)

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
