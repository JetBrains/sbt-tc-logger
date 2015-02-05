import bintray.Keys._

sbtPlugin := true

name := "sbt-teamcity-logger"

organization := "org.jetbrains.teamcity.plugins"

version := "0.1.0"

unmanagedBase := baseDirectory.value / "lib"

publishArtifact in Test := false

publishArtifact in (Compile, packageBin) := false

publishMavenStyle := false

credentials += Credentials("Artifactory Realm", "repository.jetbrains.com", System.getProperty("tc.repo.username"), System.getProperty("tc.repo.password"))

bintrayPublishSettings

repository in bintray := "sbt-teamcity-logger"

pomExtra :=
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>

