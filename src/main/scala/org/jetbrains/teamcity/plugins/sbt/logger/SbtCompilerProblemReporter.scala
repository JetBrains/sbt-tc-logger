// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.SbtApiSupport.toFilePosition
import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.compilation.{SbtCompilationFlow, SbtCompilationReporter}
import org.jetbrains.teamcity.plugins.sbt.logger.reporting.SbtCompilerInspectionReporter
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import xsbti.{Position, Problem}

import scala.collection.mutable

/**
 * The one Zinc-facing adapter that coordinates compiler inspections and Build Log diagnostics.
 *
 * Zinc exposes only one reporter.
 * This adapter retains its normal problem semantics and delegates each compiler problem to two specialized reporters:
 *  - [[org.jetbrains.teamcity.plugins.sbt.logger.buildLog.compilation.SbtCompilationReporter]]<br>
 *    Publishes structured Build Log output, starts the compilation flow, and records diagnostic summaries.
 *    It is passed in because the SBT logger shares it to start and finish the same compilation flows.
 *  - [[org.jetbrains.teamcity.plugins.sbt.logger.reporting.SbtCompilerInspectionReporter#SbtCompilerInspectionReporter]]<br>
 *    Publishes TeamCity Code Inspections. It is a primary-constructor dependency because its inspection-publication
 *    state is local to a Zinc reporter, including the state that prevents repeatedly declaring the inspection type.
 *
 * @param defaultReporter      the original Zinc reporter used when structured compiler output is disabled and for
 *                             Zinc operations that this adapter does not replace
 * @param compilationReporter  the shared Build Log reporter that starts the compilation flow and publishes compiler
 *                             diagnostics to it
 * @param inspectionReporter   the adapter-local reporter that publishes compiler problems as TeamCity Code Inspections
 * @param flow                 the Build Log flow that identifies this compiler invocation
 * @param reportCompilerOutput whether compiler output is reported through the structured Build Log flow instead of
 *                             the original Zinc reporter
 */
final class SbtCompilerProblemReporter(
  defaultReporter: xsbti.Reporter,
  compilationReporter: SbtCompilationReporter,
  inspectionReporter: SbtCompilerInspectionReporter,
  flow: SbtCompilationFlow,
  reportCompilerOutput: Boolean
) extends xsbti.Reporter {
  def this(
    defaultReporter: xsbti.Reporter,
    compilationReporter: SbtCompilationReporter,
    writer: TeamCityServiceMessageWriter,
    flow: SbtCompilationFlow,
    reportCompilerOutput: Boolean
  ) = this(
    defaultReporter,
    compilationReporter,
    new SbtCompilerInspectionReporter(writer, toFilePosition),
    flow,
    reportCompilerOutput
  )

  private val reportedProblems = mutable.ArrayBuffer.empty[Problem]

  override def reset(): Unit = synchronized {
    reportedProblems.clear()
    defaultReporter.reset()
  }

  override def hasErrors: Boolean = synchronized {
    reportedProblems.exists(_.severity() == xsbti.Severity.Error)
  }

  override def hasWarnings: Boolean = synchronized {
    reportedProblems.exists(_.severity() == xsbti.Severity.Warn)
  }

  override def printSummary(): Unit = {
    if (!reportCompilerOutput) {
      defaultReporter.printSummary()
    }
  }

  override def problems(): Array[Problem] = synchronized {
    reportedProblems.toArray
  }

  override def comment(position: Position, message: String): Unit = {
    if (!reportCompilerOutput) {
      defaultReporter.comment(position, message)
    }
  }

  override def log(problem: Problem): Unit = {
    if (reportCompilerOutput) {
      compilationReporter.started(flow)
    }

    synchronized {
      reportedProblems += problem
    }
    inspectionReporter.report(problem)

    if (reportCompilerOutput) {
      compilationReporter.reportCompilerProblem(flow, problem.severity(), formatProblem(problem))
    } else {
      defaultReporter.log(problem)
    }
  }

  private def formatProblem(problem: Problem): String = {
    val position = problem.position()
    val sourceLocation = toFilePosition(position).map { filePosition =>
      s"${filePosition.sourcePath}:${filePosition.line}: ${problem.message()}"
    }.getOrElse(problem.message())
    val sourceLine = Option(position.lineContent()).filter(_.nonEmpty)
    val pointer =
      if (position.pointerSpace().isPresent)
        Some(position.pointerSpace().get() + "^")
      else if (position.pointer().isPresent)
        Some((" " * position.pointer().get()) + "^")
      else
        None

    (Seq(sourceLocation) ++ sourceLine ++ pointer).mkString("\n")
  }
}
