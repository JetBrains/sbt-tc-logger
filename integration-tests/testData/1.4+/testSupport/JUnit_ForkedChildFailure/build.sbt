libraryDependencies ++= Seq(
  "junit" % "junit" % "4.13.2" % Test,
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test
)

Test / fork := true
Test / logBuffered := false
Test / parallelExecution := false

scalaVersion := "2.13.18"
