libraryDependencies ++= Seq(
  "com.novocode" % "junit-interface" % "0.11" % "test",
  "org.junit.vintage" % "junit-vintage-engine" % "5.3.1" % "test"
)

Test / fork := true
Test / logBuffered := false
Test / parallelExecution := false

scalaVersion := "2.12.7"
