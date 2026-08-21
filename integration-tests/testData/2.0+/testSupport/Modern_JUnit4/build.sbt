name := "modern-junit4-reporting"

scalaVersion := "3.8.4"

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.13.2" % Test,
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test
)

Test / parallelExecution := false
