import sbt.{Inc, Value}

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.13.2" % Test,
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test
)

Test / logBuffered := false
Test / parallelExecution := false

scalaVersion := "2.13.18"

lazy val verifyTestResultIsIncomplete = taskKey[Unit]("Checks that Test / test completes with Inc")

verifyTestResultIsIncomplete := {
  (Test / test).result.value match {
    case Inc(_) => streams.value.log.info("Verified Test / test result is Inc")
    case Value(_) => sys.error("Expected Test / test result to be Inc after a failed test")
  }
}
