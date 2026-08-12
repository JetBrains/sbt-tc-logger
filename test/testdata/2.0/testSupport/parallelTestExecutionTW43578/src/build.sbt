name := "ScalaTeamCityTestReporterBug"

version := "1.0"

// Scala 2.11's compiler bridge cannot run on the Java 17 runtime required by SBT 2.
scalaVersion := "2.12.20"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.9" % Test
