// TW-43578 - Verifies parallel and non-parallel ScalaTest event reporting.
name := "ScalaTeamCityTestReporterBug"

version := "1.0"

scalaVersion := "2.13.18"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.20" % Test
