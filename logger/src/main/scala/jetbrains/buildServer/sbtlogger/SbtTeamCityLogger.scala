/*
 * Copyright 2013-2021 JetBrains s.r.o.
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

import sbt.Keys._
import sbt.jetbrains.buildServer.sbtlogger.apiAdapter._
import sbt.plugins.JvmPlugin
import sbt.{Def, _}

import scala.collection.mutable

object SbtTeamCityLogger extends AutoPlugin with (State => State) {

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  def apply(state: State): State = {
    val extracted = Project.extract(state)
    val sbtLoggerVersion = System.getProperty(TC_LOGGER_PROPERTY_NAME)
    if (sbtLoggerVersion == "reloaded") {
      state
    } else {
      extracted.structure.allProjectRefs.foldLeft(state)(append(SbtTeamCityLogger.projectSettings, extracted))
    }
  }

  private def append(settings: Seq[Setting[_]], extracted: Extracted)(state: State, projectRef: ProjectRef): State = {
    val scope = projectScope(projectRef)
    val appendSettings = transformSettings(scope, projectRef.build, extracted.rootProject, settings)
    reapply(Project.session(state).appendRaw(appendSettings), state)
  }

  // copied from sbt.internal.Load
  private def transformSettings(thisScope: Scope, uri: URI, rootProject: URI => String, settings: Seq[Setting[_]]): Seq[Setting[_]] =
    Project.transform(Scope.resolveScope(thisScope, uri, rootProject), settings)

  // copied from sbt.internal.SessionSettings
  private def reapply(session: SessionSettings, s: State): State =
    BuiltinCommands.reapply(session, Project.structure(s), s)

  lazy val tcLogAppender = new TCLogAppender()
  lazy val tcLoggers: mutable.Map[String, TCLogger] = collection.mutable.Map[String, TCLogger]()
  lazy val tcTestListener = new TCReportListener(tcLogAppender)
  lazy val startCompilationLogger: TaskKey[Unit] = TaskKey[Unit]("start-compilation-logger", "runs before compile")
  lazy val startTestCompilationLogger: TaskKey[Unit] = TaskKey[Unit]("start-test-compilation-logger", "runs before compile in test")
  lazy val endCompilationLogger: TaskKey[Unit] = TaskKey[Unit]("end-compilation-logger", "runs after compile")
  lazy val endTestCompilationLogger: TaskKey[Unit] = TaskKey[Unit]("end-test-compilation-logger", "runs after compile in test")
  lazy val tcEndCompilation: TaskKey[Unit] = TaskKey[Unit]("tc-end-compilation", "")
  lazy val tcEndTestCompilation: TaskKey[Unit] = TaskKey[Unit]("tc-end-test-compilation", "")

  val tcVersion: Option[String] = sys.env.get("TEAMCITY_VERSION")
  val tcFound: Boolean = tcVersion.isDefined

  val TC_LOGGER_PROPERTY_NAME = "TEAMCITY_SBT_LOGGER_VERSION"

  val tcLoggerVersion: String = System.getProperty(TC_LOGGER_PROPERTY_NAME)
  if (tcLoggerVersion == null) {
    System.setProperty(TC_LOGGER_PROPERTY_NAME, "loaded")
  } else if (tcLoggerVersion == "loaded") {
    System.setProperty(TC_LOGGER_PROPERTY_NAME, "reloaded")
  }

  var testResultLoggerFound = true

  try {
    val _: Def.Initialize[sbt.TestResultLogger] = Def.setting {
      (testResultLogger in Test).value
    }
  } catch {
    case _: java.lang.NoSuchMethodError =>
      testResultLoggerFound = false
  }

  //noinspection TypeAnnotation,ConvertExpressionToSAM
  override lazy val projectSettings = if (tcFound && testResultLoggerFound)
    loggerOnSettings ++ Seq(
      testResultLogger in(Test, test) := new TestResultLogger {

        import sbt.Tests._

        def run(log: Logger, results: Output, taskName: String): Unit = {
          //default behaviour there is
          //TestResultLogger.SilentWhenNoTests.run(log, results, taskName)
          //we will just ignore to prevent appearing of 'exit code 1' when test failed
        }
      }
    )
  else if (tcFound) loggerOnSettings
  else loggerOffSettings


  lazy val loggerOnSettings: Seq[Def.Setting[_]] = Seq(
    commands += tcLoggerStatusCommand,
    extraLoggers := {
      val currentFunction: Def.ScopedKey[_] => Seq[ExtraLogger] = extraLoggers.value
      key: ScopedKey[_] => {
        val scope: String = getScopeId(key.scope.project)
        val logger: ExtraLogger = extraLogger(tcLoggers, tcLogAppender, scope)

        logger +: currentFunction(key)
      }
    },
    testListeners += tcTestListener,

    startCompilationLogger := tcLogAppender.compilationBlockStart(getScopeId(streams.value.key.scope.project)),
    startTestCompilationLogger := tcLogAppender.compilationTestBlockStart(getScopeId(streams.value.key.scope.project)),
    endCompilationLogger := tcLogAppender.compilationBlockEnd(getScopeId(streams.value.key.scope.project)),
    endTestCompilationLogger := tcLogAppender.compilationTestBlockEnd(getScopeId(streams.value.key.scope.project)),

    compile in Compile := ((compile in Compile) dependsOn startCompilationLogger).value,

    compile in Test := ((compile in Test) dependsOn startTestCompilationLogger).value,

    tcEndCompilation := (endCompilationLogger triggeredBy (compile in Compile)).value,

    tcEndTestCompilation := (endTestCompilationLogger triggeredBy (compile in Test)).value
  ) ++
    inConfig(Compile)(Seq(reporterSettings(tcLogAppender))) ++
    inConfig(Test)(Seq(reporterSettings(tcLogAppender)))


  lazy val loggerOffSettings: Seq[Def.Setting[_]] = Seq(
    commands += tcLoggerStatusCommand
  )

  def tcLoggerStatusCommand: Command = Command.command("sbt-teamcity-logger") {
    state => doCommand(state)
  }

  private def doCommand(state: State): State = {
    println("Plugin sbt-teamcity-logger was loaded.")
    val tcv = tcVersion.getOrElse("undefined")
    if (tcFound) {
      println(s"TeamCity version='$tcv'")
    } else {
      println(s"TeamCity was not discovered. Logger was switched off.")
    }
    state
  }

  private def getScopeId(scope: ScopeAxis[sbt.Reference]):String = {
     "" + scope.hashCode()
  }

}
