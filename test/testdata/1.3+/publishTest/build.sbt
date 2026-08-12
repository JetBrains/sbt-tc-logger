name := "Publish Test Project"

organization := "org.jetbrains"

version := "0.1-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.18"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.20"

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % "2.13.18"
)


publishMavenStyle := true

pomExtra :=
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
</licenses>
