import bintray.Keys._

sbtPlugin := true

name := "sbt-teamcity-logger"

organization := "org.jetbrains.teamcity.plugins"

version := "0.2.0"

unmanagedBase := baseDirectory.value / "lib"

publishArtifact in Test := false

publishArtifact in (Compile, packageBin) := false

publishMavenStyle := false

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))

bintrayPublishSettings

repository in bintray := "sbt-plugins"

bintrayOrganization := Some("jetbrains")

pomExtra :=
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>

