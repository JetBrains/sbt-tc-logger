resolvers += "SonaType" at "https://oss.sonatype.org/content/groups/public"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test

logBuffered in Test := false

scalaVersion := "2.12.21"

parallelExecution in test := false

testGrouping in Test := (definedTests in Test).value.map { test =>
    import Tests._
    new Group(
      name = test.name,
      tests = Seq(test),
      runPolicy = InProcess)
  }.sortWith(_.name < _.name)



libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.2"

testFrameworks += new TestFramework("org.scalatest.tools.Framework")
