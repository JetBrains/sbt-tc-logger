sbtPlugin := true

name := "sbt-teamcity-logger"

organization := "org.jetbrains.teamcity.plugins"

unmanagedBase := baseDirectory.value / "lib"

publishArtifact in Test := false
publishMavenStyle := false

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))

bintrayRepository := "sbt-plugins"
bintrayOrganization := Some("jetbrains")

pomExtra :=
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>

