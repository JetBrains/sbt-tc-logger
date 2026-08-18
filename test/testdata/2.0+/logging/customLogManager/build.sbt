import sbt.internal.LogManager
import sbt.internal.util.ConsoleAppender

ThisBuild / scalaVersion := "2.13.18"

logManager := LogManager.withScreenLogger((_, _) => ConsoleAppender())

lazy val customManagerLog = taskKey[Unit]("Log through the fixture's custom log manager.")

customManagerLog := streams.value.log.info("custom manager ordinary task message")
