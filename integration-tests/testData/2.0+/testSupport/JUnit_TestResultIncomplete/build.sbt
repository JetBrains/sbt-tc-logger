import sbt.Result.{Inc, Value}

libraryDependencies ++= Seq(
  "com.novocode" % "junit-interface" % "0.11" % "test",
  "org.junit.vintage" % "junit-vintage-engine" % "5.3.1" % "test"
)

Test / logBuffered := false
Test / parallelExecution := false

scalaVersion := "2.12.7"

lazy val verifyTestResultIsIncomplete = taskKey[Unit]("Checks that Test / test completes with Inc")

verifyTestResultIsIncomplete := Def.uncached {
  // SBT 2 exposes test as an InputKey, so create its no-argument task before observing the Result enum.
  (Test / test).toTask("").result.value match {
    case Inc(_) => streams.value.log.info("Verified Test / test result is Inc")
    case Value(_) => sys.error("Expected Test / test result to be Inc after a failed test")
  }
}
