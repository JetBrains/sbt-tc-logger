/*
 * Copyright 2013-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

package jetbrains.buildServer.sbtlogger

import sbt._
import Keys._

object SbtTeamCityLogger extends Plugin with (State => State) {

  def apply(state: State) = {
      val extracted = Project.extract(state)
      val sbtLoggerVersion = System.getProperty(TC_LOGGER_PROPERTY_NAME)
      if (sbtLoggerVersion=="reloaded"){
        state
      } else {
        extracted.structure.allProjectRefs.foldLeft(state)(append(SbtTeamCityLogger.projectSettings, extracted))
      }
  }

  private def append(settings: Seq[Setting[_]], extracted: Extracted)(state: State, projectRef: ProjectRef): State = {
    val scope = Load.projectScope(projectRef)
    val appendSettings = Load.transformSettings(scope, projectRef.build, extracted.rootProject, settings)
    SessionSettings.reapply(Project.session(state).appendRaw(appendSettings), state)
  }

  lazy val tcLogAppender = new TCLogAppender()
  lazy val tcLogger = new TCLogger(tcLogAppender)
  lazy val tcTestListener = new TCReportListener(tcLogAppender)
  lazy val startCompilationLogger = TaskKey[Unit]("start-compilation-logger", "runs before compile")
  lazy val startTestCompilationLogger = TaskKey[Unit]("start-test-compilation-logger", "runs before compile in test")

  val tcVersion = sys.env.get("TEAMCITY_VERSION")
  val tcFound = !tcVersion.isEmpty

  val TC_LOGGER_PROPERTY_NAME = "TEAMCITY_SBT_LOGGER_VERSION"

  val tcLoggerVersion = System.getProperty(TC_LOGGER_PROPERTY_NAME)
  if (tcLoggerVersion==null){
      System.setProperty(TC_LOGGER_PROPERTY_NAME,"loaded")
  } else if (tcLoggerVersion=="loaded"){
      System.setProperty(TC_LOGGER_PROPERTY_NAME,"reloaded")
  }

  override lazy val projectSettings = if (tcFound) loggerOnSettings else loggerOffSettings

   lazy val loggerOnSettings = Seq(
        commands += tcLoggerStatusCommand,
        extraLoggers := {
          val currentFunction = extraLoggers.value
          (key: ScopedKey[_]) => {
            tcLogger +: currentFunction(key)
          }
        },
        testResultLogger in (Test, test) := new TestResultLogger {
            import sbt.Tests._
            def run(log: Logger, results: Output, taskName: String): Unit = {
                //default behaviour there is
                //TestResultLogger.SilentWhenNoTests.run(log, results, taskName)
                //we will just ignore to prevet exit code 1 appears in case when test failed
            }
        },
        testListeners += tcTestListener,
        startCompilationLogger := {
              tcLogAppender.compilationBlockStart()
        },
        startTestCompilationLogger := {
              tcLogAppender.compilationTestBlockStart()
        },
        compile in Compile <<= ((compile in Compile) dependsOn startCompilationLogger)
              andFinally {tcLogAppender.compilationBlockEnd()},

        compile in Test <<= ((compile in Test) dependsOn startTestCompilationLogger)
               andFinally {tcLogAppender.compilationTestBlockEnd()}
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

