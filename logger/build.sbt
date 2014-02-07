sbtPlugin := true

name := "sbt-teamcity-logger"

organization := "org.jetbrains.teamcity.plugins"

version := "0.1." + System.getProperty("teamcity.test") + "-SNAPSHOT"

unmanagedBase := baseDirectory.value / "lib"

publishArtifact in Test := false

publishMavenStyle := false

pomExtra :=
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>

