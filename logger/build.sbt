sbtPlugin := true

name := "sbt-teamcity-logger"

organization := "org.jetbrains.teamcity.plugins"

crossSbtVersions := Seq("0.13.16","1.0.0")

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))

publishArtifact in Test := false
publishMavenStyle := false
bintrayOrganization := Some("jetbrains")
bintrayRepository := "sbt-plugins"
bintrayVcsUrl := Option("https://github.com/JetBrains/sbt-teamcity-logger")

pomExtra :=
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>

artifact in (Compile, assembly) := {
  val art = (artifact in (Compile, assembly)).value
  art.withClassifier(Some("assembly"))
}

addArtifact(artifact in (Compile, assembly), assembly)

assemblyJarName in assembly := "sbt-teamcity-logger.jar"