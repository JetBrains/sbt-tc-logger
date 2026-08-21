// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage._
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter

/** Publishes compiler problems as TeamCity Code Inspections without creating Build Log output. */
final class SbtCompilerInspectionReporter(
  writer: TeamCityServiceMessageWriter,
  toFilePosition: xsbti.Position => Option[SbtCompilerInspectionReporter.FilePosition]
) {
  import SbtCompilerInspectionReporter._

  private var inspectionTypeDeclared = false

  def report(problem: xsbti.Problem): Unit = {
    declareInspectionType()
    toFilePosition(problem.position()).foreach { position =>
      writer.write(CompilerInspection(
        SbtCompileProblemInspectionType,
        problem.message(),
        position.sourcePath,
        position.line,
        inspectionSeverity(problem.severity())
      ))
    }
  }

  private def declareInspectionType(): Unit = synchronized {
    if (!inspectionTypeDeclared) {
      writer.write(SbtCompileProblemInspectionTypeMessage)
      inspectionTypeDeclared = true
    }
  }
}

object SbtCompilerInspectionReporter {
  final case class FilePosition(sourcePath: String, line: Int)

  val SbtCompileProblemInspectionType: String =
    "SbtCompileProblem"

  val SbtCompileProblemInspectionTypeMessage: CompilerInspectionType =
    CompilerInspectionType(SbtCompileProblemInspectionType, "sbt compile problem", "Compile problems", "Compile problems")

  private def inspectionSeverity(severity: xsbti.Severity): InspectionSeverity = {
    import xsbti.Severity._
    severity match {
      case Info => InspectionSeverity.Info
      case Warn => InspectionSeverity.Warning
      case Error => InspectionSeverity.Error
    }
  }
}
