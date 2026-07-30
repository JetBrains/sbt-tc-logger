/*
 * Copyright 2013-2021 JetBrains s.r.o.
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

package sbt.jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.TCCompilerReporter.FilePosition
import jetbrains.buildServer.sbtlogger.{TCCompilerReporter, TCLogAppender, TCLogger}
import sbt.internal.AppenderSupplier
import sbt.internal.util.Appender
import sbt.{Def, Reference, Scope, Select, Zero}
import xsbti.Problem

import scala.collection.mutable

/** SBT 2 compatibility boundary for the shared TeamCity logger implementation. */
object apiAdapter {

  type SessionSettings = sbt.internal.SessionSettings
  type ExtraLogger = Appender

  val silentTestResultLogger: sbt.TestResultLogger = new sbt.TestResultLogger {
    def run(log: sbt.util.Logger, results: sbt.Tests.Output, taskName: String): Unit = ()
  }

  def projectScope(project: Reference): Scope = Scope(Select(project), Zero, Zero, Zero)

  def extraLogger(tcLoggers: mutable.Map[String, TCLogger],
                  tcLogAppender: TCLogAppender,
                  scope: String): ExtraLogger = {
    val appender = new TCLoggerAppender(tcLogAppender, scope)
    appender
  }

  def reporterSettings(tcLogAppender: TCLogAppender): Def.Setting[?] = {
    import sbt.Keys.compile
    compile / Unhide.compilerReporter := Def.uncached {
      val defaultReporter = (compile / Unhide.compilerReporter).value
      new TCCompilerReporter(defaultReporter)
    }
  }

  def toFilePosition(position: xsbti.Position): Option[FilePosition] = {
    val path = position.sourcePath()
    val maybeLine = position.line()
    val line = if (maybeLine.isPresent) maybeLine.get().intValue() else 0
    if (path.isPresent) Some(FilePosition(path.get(), line))
    else None
  }

  abstract class ReporterAdapter(delegate: xsbti.Reporter) extends xsbti.Reporter {
    def delegateLog(problem: Problem): Unit = {
      delegate.log(problem)
    }
  }
}
