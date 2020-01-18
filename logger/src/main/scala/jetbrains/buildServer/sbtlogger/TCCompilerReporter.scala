/*
 * Copyright 2013-2020 JetBrains s.r.o.
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
import jetbrains.buildServer.sbtlogger.TCCompilerReporter._
import sbt.jetbrains.buildServer.sbtlogger.apiAdapter.{ReporterAdapter, toFilePosition}
import xsbti.{Position, Problem}

class TCCompilerReporter(delegate: xsbti.Reporter) extends ReporterAdapter(delegate) {

  println(SbtCompileProblemInspectionTypeMessage.toMessageString)

  override def reset(): Unit = delegate.reset()
  override def hasErrors: Boolean = delegate.hasErrors
  override def hasWarnings: Boolean = delegate.hasWarnings
  override def printSummary(): Unit = delegate.printSummary()
  override def problems(): Array[Problem] = delegate.problems()
  override def comment(pos: Position, msg: String): Unit = delegate.comment(pos,msg)

  override def log(problem: Problem): Unit = {
    logInspection(problem)
    delegateLog(problem)
  }

  def logInspection(problem: Problem): Unit = {
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
    import xsbti.Severity._
    severity match {
      case Info => "INFO"
      case Warn => "WARNING"
      case Error => "ERROR"
    }
  }
}