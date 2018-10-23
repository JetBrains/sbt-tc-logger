/*
 * Copyright 2013-2017 JetBrains s.r.o.
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

package sbt.jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.SbtTeamCityLogger.{tcLogAppender, tcLoggers}
import jetbrains.buildServer.sbtlogger.TCCompilerReporter.FilePosition
import jetbrains.buildServer.sbtlogger.{TCCompilerReporter, TCLogAppender, TCLogger}
import sbt.{Def, Global, Reference, Scope, Select, Zero, LoggerReporter}
import xsbti.{Problem, Severity, Position, Reporter}

import scala.collection.mutable

object apiAdapter {

  type TestResult = sbt.TestResult.Value
  type ExtraLogger = sbt.AbstractLogger

  def extraLogger(tcLoggers: mutable.Map[String, TCLogger],
                  tcLogAppender: TCLogAppender,
                  scope: String): ExtraLogger = {

    tcLoggers.get(scope) match {
      case Some(l) => l
      case _ =>
        val logger = new TCLogger(tcLogAppender, scope)
        tcLoggers.put(scope, logger)
        logger
    }
  }

  // copied from sbt.internal.Load
  def projectScope(project: Reference): Scope = Scope(Select(project), Global, Global, Global)

  def reporterSettings(tcLogAppender: TCLogAppender): Def.Setting[_] = {
    import sbt.Keys.compile
    Unhide.compilerReporter in compile := {
      val maybeDefaultReporter = (Unhide.compilerReporter in compile).value
      val delegate = maybeDefaultReporter.getOrElse {
        val logger = new TCLogger(tcLogAppender, "NONE")
        new LoggerReporter(9000, logger)
      }
      val tcReporter = new TCCompilerReporter(delegate)
      Some(tcReporter)
    }
  }

  def toFilePosition(position: Position): Option[FilePosition] = {
    val path = position.sourcePath()
    val maybeLine = position.line()
    val line = if (maybeLine.isDefined) maybeLine.get().intValue() else 0
    if (path.isDefined) Some(FilePosition(path.get(),line))
    else None
  }

  abstract class ReporterAdapter(delegate: Reporter) extends Reporter {

    def log(problem: Problem): Unit

    def delegateLog(problem: Problem) = {
      delegate.log(problem.position(), problem.message(), problem.severity())
    }

    override def log(position: Position, msg: String, severity: Severity): Unit = {
      val problem = new sbt.compiler.javac.JavaProblem(position, severity, msg)
      log(problem)
    }
  }
}
