/*
 * Copyright 2013-2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.sbtlogger

import sbt.Keys.*
import sbt.internal.AppenderSupplier
import sbt.internal.util.Appender
import sbt.jetbrains.buildServer.sbtlogger.apiAdapter.*
import sbt.plugins.JvmPlugin
import sbt.{Def, *}

import scala.collection.mutable

/** SBT 2 implementation using its AppenderSupplier logging API. */
object SbtTeamCityLogger extends AutoPlugin with (State => State) {

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  def apply(state: State): State = {
    val sbtLoggerVersion = System.getProperty(TC_LOGGER_PROPERTY_NAME)
    if (sbtLoggerVersion == "reloaded") return state

    val extracted = Project.extract(state)
    import extracted.{structure => extractedStructure, *}
    val transformedProjectSettings = extractedStructure.allProjectRefs.flatMap { projectRef =>
      transformSettings(projectScope(projectRef), projectRef.build, rootProject, SbtTeamCityLogger.projectSettings)
    }
    reapply(session.appendRaw(transformedProjectSettings), state)
  }

  private def transformSettings(thisScope: Scope, uri: URI, rootProject: URI => String, settings: Seq[Setting[?]]): Seq[Setting[?]] =
    Project.transform(Scope.resolveScope(thisScope, uri, rootProject), settings)

  private def reapply(session: SessionSettings, state: State): State =
    BuiltinCommands.reapply(session, Project.structure(state), state)

  lazy val tcLogAppender = new TCLogAppender()
  lazy val tcLoggers: mutable.Map[String, TCLogger] = collection.mutable.Map[String, TCLogger]()
  lazy val tcTestListener = new TCReportListener(tcLogAppender)
  lazy val startCompilationLogger = taskKey[Unit]("start-compilation-logger")
  lazy val startTestCompilationLogger = taskKey[Unit]("start-test-compilation-logger")
  lazy val endCompilationLogger = taskKey[Unit]("end-compilation-logger")
  lazy val endTestCompilationLogger = taskKey[Unit]("end-test-compilation-logger")

  val tcVersion: Option[String] = sys.env.get("TEAMCITY_VERSION")
  val tcFound: Boolean = tcVersion.isDefined

  val TC_LOGGER_PROPERTY_NAME = "TEAMCITY_SBT_LOGGER_VERSION"

  val tcLoggerVersion: String = System.getProperty(TC_LOGGER_PROPERTY_NAME)
  if (tcLoggerVersion == null) System.setProperty(TC_LOGGER_PROPERTY_NAME, "loaded")
  else if (tcLoggerVersion == "loaded") System.setProperty(TC_LOGGER_PROPERTY_NAME, "reloaded")

  var testResultLoggerFound = true

  try {
    val _: Def.Initialize[TestResultLogger] = Def.setting {
      (Test / test / testResultLogger).value
    }
  } catch {
    case _: java.lang.NoSuchMethodError => testResultLoggerFound = false
  }

  override lazy val projectSettings =
    if (tcFound && testResultLoggerFound)
      loggerOnSettings ++ Seq(
        // SBT 2 dispatches the `test` command through testQuick or testFull.
        // Keep all result-loggers silent: TeamCity receives the individual
        // test events and a failed test must not turn the nested SBT process
        // into an infrastructure failure.
        Test / test / testResultLogger := silentTestResultLogger,
        Test / testQuick / testResultLogger := silentTestResultLogger,
        Test / testFull / testResultLogger := silentTestResultLogger
      )
    else if (tcFound) loggerOnSettings
    else loggerOffSettings

  lazy val loggerOnSettings: Seq[Def.Setting[?]] = Seq(
    commands += tcLoggerStatusCommand,
    extraAppenders := {
      val current = extraAppenders.value
      new AppenderSupplier {
        override def apply(key: ScopedKey[?]): Seq[Appender] = {
          val scope = getScopeId(key.scope.project)
          extraLogger(tcLoggers, tcLogAppender, scope) +: current(key)
        }
      }
    },
    Test / testListeners += tcTestListener,
    startCompilationLogger := tcLogAppender.compilationBlockStart(getScopeId(streams.value.key.scope.project)),
    startTestCompilationLogger := tcLogAppender.compilationTestBlockStart(getScopeId(streams.value.key.scope.project)),
    endCompilationLogger := tcLogAppender.compilationBlockEnd(getScopeId(streams.value.key.scope.project)),
    endTestCompilationLogger := tcLogAppender.compilationTestBlockEnd(getScopeId(streams.value.key.scope.project)),
    // In SBT 2 a task merely triggered by `compile` is not guaranteed to be
    // scheduled after the compile task. Compose the underlying tasks directly
    // instead, preserving the start dependency and always running the matching
    // completion task, including after a compilation failure.
    Compile / compile := Def.uncached {
      Def.taskDyn {
        val result = (Compile / compile).dependsOn(startCompilationLogger).result.value
        Def.task {
          endCompilationLogger.value
          result match {
            case Result.Value(value) => value
            case Result.Inc(cause) => throw cause
          }
        }
      }.value
    },
    Test / compile := Def.uncached {
      Def.taskDyn {
        val result = (Test / compile).dependsOn(startTestCompilationLogger).result.value
        Def.task {
          endTestCompilationLogger.value
          result match {
            case Result.Value(value) => value
            case Result.Inc(cause) => throw cause
          }
        }
      }.value
    },
  ) ++
    inConfig(Compile)(Seq(reporterSettings(tcLogAppender))) ++
    inConfig(Test)(Seq(reporterSettings(tcLogAppender)))

  lazy val loggerOffSettings: Seq[Def.Setting[?]] = Seq(
    commands += tcLoggerStatusCommand
  )

  def tcLoggerStatusCommand: Command = Command.command("sbt-teamcity-logger") { state =>
    println("Plugin sbt-teamcity-logger was loaded.")
    tcVersion match {
      case Some(version) => println(s"TeamCity version='$version'")
      case None => println("TeamCity was not discovered. Logger was switched off.")
    }
    state
  }

  private def getScopeId(scope: ScopeAxis[Reference]): String = scope.hashCode().toString
}
