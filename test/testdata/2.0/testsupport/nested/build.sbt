resolvers += "SonaType" at "https://oss.sonatype.org/content/groups/public"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.2.4" % "test"

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % "2.10.4"
)

Test / logBuffered := false

scalaVersion := "2.10.4"

Test / parallelExecution := false

(Test / testGrouping) := (Test / definedTests).value.map { test =>
  import Tests._
  new Group(
    name = test.name,
    tests = Seq(test),
    runPolicy = InProcess)
}.sortWith(_.name < _.name)
