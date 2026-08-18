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
import jetbrains.buildServer.messages.serviceMessages.MapSerializerUtil
import jetbrains.buildServer.sbtlogger.TCCompilerReporter.*
import sbt.jetbrains.buildServer.sbtlogger.apiAdapter.{ReporterAdapter, toFilePosition}
import xsbti.{Position, Problem}

import scala.collection.mutable

/**
 * Reports compiler diagnostics through the active TeamCity compilation flow.
 *
 * The default SBT reporter writes the same diagnostic directly to the console.
 * That output has no TeamCity flow ID, so it is rendered outside the compiler
 * node and duplicates the structured message.  Keeping the problem state here
 * lets Zinc observe errors and warnings normally while leaving TeamCity with one
 * complete, correctly scoped diagnostic.
 */
class TCCompilerReporter(
  delegate: xsbti.Reporter,
  appender: TCLogAppender,
  flowId: String,
  ensureCompilationStarted: () => Unit
) extends ReporterAdapter(delegate) {

  private val reportedProblems = mutable.ArrayBuffer.empty[Problem]
  private var inspectionTypeDeclared = false

  override def reset(): Unit = synchronized {
    reportedProblems.clear()
    delegate.reset()
  }

  override def hasErrors: Boolean = synchronized {
    reportedProblems.exists(_.severity() == xsbti.Severity.Error)
  }

  override def hasWarnings: Boolean = synchronized {
    reportedProblems.exists(_.severity() == xsbti.Severity.Warn)
  }

  // SBT invokes this after compilation.  The structured diagnostics above are
  // more useful than its generic "one error found" summary.
  override def printSummary(): Unit = ()

  override def problems(): Array[Problem] = synchronized {
    reportedProblems.toArray
  }

  override def comment(pos: Position, msg: String): Unit = ()

  override def log(problem: Problem): Unit = {
    ensureCompilationStarted()
    declareInspectionType()
    synchronized {
      reportedProblems += problem
    }
    logInspection(problem)
    appender.log(logLevel(problem.severity()), formatProblem(problem), flowId)
  }

  private def declareInspectionType(): Unit = synchronized {
    if (!inspectionTypeDeclared) {
      println(SbtCompileProblemInspectionTypeMessage.toMessageString)
      inspectionTypeDeclared = true
    }
  }

  private def logInspection(problem: Problem): Unit = {
    inspectionMessage(problem).foreach { msg =>
      println(msg.toMessageString)
    }
  }
}

object TCCompilerReporter {

  case class FilePosition(sourcePath: String, line: Int)

  case class ServerMessage(name: String, attributes: Map[String,String]) {
    def toMessageString: String = {
      val attributeString = attributes.map {
        case (k, v) => s"$k='${MapSerializerUtil.escapeStr(v,MapSerializerUtil.STD_ESCAPER2)}'"
      }.mkString(" ")

      s"##teamcity[$name $attributeString]"
    }
  }

  private def logLevel(severity: xsbti.Severity): String = {
    import xsbti.Severity.*
    severity match {
      case Info => "INFO"
      case Warn => "WARN"
      case Error => "ERROR"
    }
  }

  private def formatProblem(problem: Problem): String = {
    val position = problem.position()
    val sourceLocation = toFilePosition(position).map { filePosition =>
      s"${filePosition.sourcePath}:${filePosition.line}: ${problem.message()}"
    }.getOrElse(problem.message())
    val sourceLine = Option(position.lineContent()).filter(_.nonEmpty)
    val pointer =
      if (position.pointerSpace().isPresent) Some(position.pointerSpace().get() + "^")
      else if (position.pointer().isPresent) Some((" " * position.pointer().get()) + "^")
      else None

    (Seq(sourceLocation) ++ sourceLine ++ pointer).mkString("\n")
  }

  def inspectionMessage(problem: Problem): Option[ServerMessage] = {
    val maybeFilePosition = toFilePosition(problem.position())

    maybeFilePosition.map { filePosition =>
      val inspectionAttributes = Map(
        "typeId" -> SbtCompileProblemInspectionType,
        "message" -> problem.message(),
        "file" -> filePosition.sourcePath,
        "line" -> filePosition.line.toString,
        "SEVERITY" -> inspectionSeverity(problem.severity())
      )

      ServerMessage("inspection", inspectionAttributes)
    }
  }

  val SbtCompileProblemInspectionType: String = "SbtCompileProblem"

  val SbtCompileProblemInspectionTypeMessage: ServerMessage = {
    val attributes = Map(
      "id" -> SbtCompileProblemInspectionType,
      "name" -> "sbt compile problem",
      "description" -> "Compile problems",
      "category" -> "Compile problems"
    )
    ServerMessage("inspectionType", attributes)
  }

  private def inspectionSeverity(severity: xsbti.Severity): String = {
    import xsbti.Severity.*
    severity match {
      case Info => "INFO"
      case Warn => "WARNING"
      case Error => "ERROR"
    }
  }
}
