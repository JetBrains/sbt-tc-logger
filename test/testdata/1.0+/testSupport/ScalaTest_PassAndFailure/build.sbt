resolvers += "SonaType" at "https://oss.sonatype.org/content/groups/public"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.0" % "test"

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % "2.10.4"
)

logBuffered in Test := false

scalaVersion := "2.10.4"

parallelExecution in test := false

