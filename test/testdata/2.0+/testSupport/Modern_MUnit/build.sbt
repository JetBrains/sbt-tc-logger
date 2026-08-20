name := "modern-munit-reporting"

scalaVersion := "3.8.4"

libraryDependencies += "org.scalameta" %% "munit" % "1.3.5" % Test

Test / parallelExecution := false
Test / testOptions += Tests.Argument("+l")
