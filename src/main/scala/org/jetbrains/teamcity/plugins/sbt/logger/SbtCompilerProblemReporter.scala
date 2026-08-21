// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.SbtApiAdapter.{ReporterAdapter, toFilePosition}
import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildEventReporter
import org.jetbrains.teamcity.plugins.sbt.logger.reporting.SbtCompilerInspectionReporter
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import xsbti.{Position, Problem}

import scala.collection.mutable

/**
 * The one Zinc-facing adapter that coordinates compiler inspections and Build Log diagnostics.
 *
 * Zinc exposes only one reporter. This adapter retains its normal problem semantics while delegating inspection
 * publication and structured Build Log output to their independently scoped collaborators.
 */
final class SbtCompilerProblemReporter(
  delegate: xsbti.Reporter,
  buildEventReporter: SbtBuildEventReporter,
  writer: TeamCityServiceMessageWriter,
  flowId: String,
  ensureCompilationStarted: () => Unit,
  reportCompilerOutput: Boolean
) extends ReporterAdapter(delegate) {
  private val reportedProblems = mutable.ArrayBuffer.empty[Problem]
  private val inspectionReporter = new SbtCompilerInspectionReporter(writer, toFilePosition)

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

  override def printSummary(): Unit = {
    if (!reportCompilerOutput) delegate.printSummary()
  }

  override def problems(): Array[Problem] = synchronized {
    reportedProblems.toArray
  }

  override def comment(position: Position, message: String): Unit = {
    if (!reportCompilerOutput) delegate.comment(position, message)
  }

  override def log(problem: Problem): Unit = {
    if (reportCompilerOutput) ensureCompilationStarted()
    synchronized {
      reportedProblems += problem
    }
    inspectionReporter.report(problem)
    if (reportCompilerOutput) {
      buildEventReporter.recordCompilerProblem(flowId, problem.severity())
      buildEventReporter.log(logLevel(problem.severity()), formatProblem(problem), flowId)
    } else delegateLog(problem)
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
}
