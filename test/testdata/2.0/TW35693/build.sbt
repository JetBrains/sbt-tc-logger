resolvers += "SonaType" at "https://oss.sonatype.org/content/groups/public"

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



libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.2"

testFrameworks += new TestFramework("org.scalatest.tools.Framework")
