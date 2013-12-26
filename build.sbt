sbtPlugin := true

name := "sbt-teamcity-logger"

organization := "org.jetbrains.teamcity.plugins"

version := "0.1.0-SNAPSHOT"

unmanagedBase := baseDirectory.value / "lib"

publishArtifact in Test := false

publishTo := Some("JetBrains TeamCity Repository" at "http://repository.jetbrains.com/teamcity")

publishMavenStyle := false

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

pomExtra :=
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>