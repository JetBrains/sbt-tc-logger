name := "Publish Test Project"

organization := "org.jetbrains"

version := "0.1-SNAPSHOT"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.0"

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % "2.10.4"
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

