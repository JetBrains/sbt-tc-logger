sbtPlugin := true

name := "sbt-teamcity-logger"

organization := "org.jetbrains.teamcity.plugins"

version := "0.1.0-SNAPSHOT"

resolvers += "JetBrains Maven Repository" at "http://repository.jetbrains.com/all"

libraryDependencies ++= Seq(
  "org.jetbrains.teamcity" % "common-api" % "8.0"
)

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