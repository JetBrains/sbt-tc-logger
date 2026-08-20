name := "modern-jupiter-reporting"

scalaVersion := "3.8.4"

libraryDependencies ++= Seq(
  "org.junit.jupiter" % "junit-jupiter-api" % "6.0.3" % Test,
  "com.github.sbt.junit" % "jupiter-interface" % "0.19.0" % Test
)

Test / parallelExecution := false
