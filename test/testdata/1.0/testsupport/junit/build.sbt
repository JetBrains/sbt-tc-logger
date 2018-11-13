
libraryDependencies ++= Seq(
  "com.novocode" % "junit-interface" % "0.11" % "test",
  "org.junit.vintage" % "junit-vintage-engine" % "5.3.1" % "test"
)

logBuffered in Test := false

scalaVersion := "2.12.7"

parallelExecution in test := false

