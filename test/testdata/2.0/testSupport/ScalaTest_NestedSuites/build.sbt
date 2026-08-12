libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test

Test / logBuffered := false

scalaVersion := "3.8.4"

Test / parallelExecution := false

(Test / testGrouping) := Def.uncached {
  (Test / definedTests).value.map { test =>
    import Tests._
    new Group(
      name = test.name,
      tests = Seq(test),
      runPolicy = InProcess)
  }.sortWith(_.name < _.name)
}
