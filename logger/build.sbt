sbtPlugin := true

name := "sbt-teamcity-logger"

organization := "org.jetbrains.teamcity.plugins"

version := "0.1.0-SNAPSHOT"

unmanagedBase := baseDirectory.value / "lib"

publishArtifact in Test := false

publishArtifact in (Compile, packageBin) := false

publishMavenStyle := false

credentials += Credentials("Artifactory Realm", "repository.jetbrains.com", System.getProperty("tc.repo.username"), System.getProperty("tc.repo.password"))

resolvers += Resolver.url("Artifactory Realm", url("http://repository.jetbrains.com/teamcity/"))

publishTo := Some("Artifactory Realm"  at "http://repository.jetbrains.com/teamcity/")

pomExtra :=
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>

