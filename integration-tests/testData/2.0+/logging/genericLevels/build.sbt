ThisBuild / scalaVersion := "2.13.18"

lazy val genericLevels = taskKey[Unit]("Emit one message at every SBT logger level.")

genericLevels := {
  val log = streams.value.log
  log.debug("generic debug message")
  log.info("generic info message")
  log.warn("generic warning message")
  log.error("generic error message")
}
