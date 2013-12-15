package com.jetbrains.sbt4tc

import sbt._
import sbt.Keys._


object SbtTeamCityLogger extends Plugin {

  lazy val tcLogAppender = new TCLogAppender()
  lazy val tcLogger = new TCLogger(tcLogAppender)
  lazy val tcTestListener = new TCReportListener(tcLogAppender)
  lazy val startCompilationLogger = TaskKey[Unit]("start-compilation-logger", "runs before compile")
  lazy val endCompilationLogger = TaskKey[Unit]("end-compilation-logger", "runs after compile")

  val tcVersion = sys.env.get("TEAMCITY_VERSION")
  val tcFound = !tcVersion.isEmpty

  override lazy val settings = if (tcFound) loggerOnSettings else loggerOffSettings

   lazy val loggerOnSettings =  Seq(
        commands += tcLoggerStatusCommand,
        testListeners += tcTestListener,
        extraLoggers := {
          val currentFunction = extraLoggers.value
          (key: ScopedKey[_]) => {
            tcLogger +: currentFunction(key)
          }
        },
        startCompilationLogger := {
             tcLogAppender.compilationBlockStart(Thread.currentThread().getId())
        },
        compile <<= ((compile in Compile) dependsOn startCompilationLogger) map { result =>
             tcLogAppender.compilationBlockEnd(Thread.currentThread().getId())
             result
        }
  )

  lazy val loggerOffSettings = Seq(
        commands += tcLoggerStatusCommand
  )


  def tcLoggerStatusCommand = Command.command("sbt-teamcity-logger") {
    state => doCommand(state)
  }

  private def doCommand(state: State): State = {
    println("Plugin sbt-teamcity-logger was loaded.")
    if (tcFound) {
      println(s"TeamCity version='$tcVersion'")
    } else {
      println(s"TeamCity was not discovered. Logger was switched off.")
    }
    state
  }


}
