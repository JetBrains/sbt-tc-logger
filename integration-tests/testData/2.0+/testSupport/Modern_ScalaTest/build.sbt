name := "modern-scalatest-reporting"

scalaVersion := "3.8.4"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.20" % Test

Test / parallelExecution := false
