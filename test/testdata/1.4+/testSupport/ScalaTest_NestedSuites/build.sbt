// The suite layout is intentionally kept in-process to preserve nested-suite event ordering.

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.20" % Test

Test / logBuffered := false

scalaVersion := "2.13.18"

Test / parallelExecution := false

Test / testGrouping := (Test / definedTests).value.map { test =>
  import Tests._
  new Group(
    name = test.name,
    tests = Seq(test),
    runPolicy = InProcess)
}.sortWith(_.name < _.name)
