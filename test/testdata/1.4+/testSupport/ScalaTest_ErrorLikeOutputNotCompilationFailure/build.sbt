// TW-35693 - Exercises error-like ScalaTest output without reporting a compilation failure.

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



// Logback 1.6.2 requires Java 11; this fixture is intentionally skipped only by the JDK 8 suite.
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.6.2"

testFrameworks += new TestFramework("org.scalatest.tools.Framework")
