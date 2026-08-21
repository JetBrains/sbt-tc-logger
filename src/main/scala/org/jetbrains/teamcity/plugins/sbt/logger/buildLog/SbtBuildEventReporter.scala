// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildEventReporter.CompilerProblemCounts

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.*
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter

/**
 * Reports SBT task output and compilation lifecycle events to TeamCity's Build Log.
 *
 * This is deliberately a concrete coordinator, rather than a broad event interface: SBT adapters share the
 * lifecycle state that keeps concurrent compiler output in its correct TeamCity flow. Tests and inspections are
 * emitted by the separate `sbt.reporting` path.
 */
final class SbtBuildEventReporter(writer: TeamCityServiceMessageWriter) {
  val CompilerDisplayName = "Scala compiler"

  private val activeCompilationFlows = ConcurrentHashMap.newKeySet[String]()
  private val compilationFlowGenerations = new ConcurrentHashMap[String, AtomicLong]()
  private val compilerProblemCounts = new ConcurrentHashMap[String, CompilerProblemCounts]()

  def log(level: _root_.sbt.Level.Value, message: => String, flowId: String): Unit =
    log(statusFor(level), level.toString, message, Some(flowId))

  def log(level: String, message: => String, flowId: String): Unit =
    log(statusFor(level), level, message, Some(flowId))

  /** Routes a compiler-task event to its compilation flow only after that flow has started. */
  def logCompilerMessage(level: _root_.sbt.Level.Value, message: => String, compilerFlowId: String): Unit = {
    val text = message
    if (activeCompilationFlows.contains(compilerFlowId)) log(level, text, compilerFlowId)
    else log(statusFor(level), level.toString, text, None)
  }

  /** Records Zinc's summary counts while structured compiler output is active. */
  def recordCompilerProblem(flowId: String, severity: xsbti.Severity): Unit = {
    val counts = compilerProblemCounts.computeIfAbsent(flowId, _ => new CompilerProblemCounts)
    counts.record(severity)
  }

  private[logger] def compilationStartedCallback(flowId: String, projectName: Option[String]): () => Unit =
    guardedCompilationStart(flowId, projectName, inTest = false)

  private[logger] def testCompilationStartedCallback(flowId: String, projectName: Option[String]): () => Unit =
    guardedCompilationStart(flowId, projectName, inTest = true)

  def compilationStarted(flowId: String, projectName: Option[String]): Unit =
    startCompilation(flowId, projectName, inTest = false)

  def compilationFinished(flowId: String, projectName: Option[String]): Unit =
    finishCompilation(flowId, projectName, inTest = false)

  def testCompilationStarted(flowId: String, projectName: Option[String]): Unit =
    startCompilation(flowId, projectName, inTest = true)

  def testCompilationFinished(flowId: String, projectName: Option[String]): Unit =
    finishCompilation(flowId, projectName, inTest = true)

  private def log(status: BuildLogStatus, level: String, message: => String, flowId: Option[String]): Unit = {
    val text = message
    writer.write(BuildLogMessage(status, withLevelPrefix(level, text), flowId))
  }

  private def statusFor(level: _root_.sbt.Level.Value): BuildLogStatus = level match {
    case _root_.sbt.Level.Error => BuildLogStatus.Error
    case _root_.sbt.Level.Warn => BuildLogStatus.Warning
    case _ => BuildLogStatus.Normal
  }

  private def statusFor(level: String): BuildLogStatus = level match {
    case "ERROR" => BuildLogStatus.Error
    case "WARN" => BuildLogStatus.Warning
    case _ => BuildLogStatus.Normal
  }

  private def startCompilation(flowId: String, projectName: Option[String], inTest: Boolean): Unit = {
    val generation = compilationGeneration(flowId)
    generation.synchronized {
      if (activeCompilationFlows.add(flowId)) {
        compilerProblemCounts.remove(flowId)
        writer.write(CompilationStarted(compilerName(projectName, inTest), flowId))
      }
    }
  }

  private def finishCompilation(flowId: String, projectName: Option[String], inTest: Boolean): Unit = {
    val generation = compilationGeneration(flowId)
    generation.synchronized {
      if (activeCompilationFlows.remove(flowId)) {
        flushCompilerSummary(flowId)
        writer.write(CompilationFinished(compilerName(projectName, inTest), flowId))
      }
      generation.incrementAndGet()
    }
  }

  private def guardedCompilationStart(flowId: String, projectName: Option[String], inTest: Boolean): () => Unit = {
    val generation = compilationGeneration(flowId)
    val expectedGeneration = generation.get()
    () => generation.synchronized {
      if (generation.get() == expectedGeneration && activeCompilationFlows.add(flowId)) {
        compilerProblemCounts.remove(flowId)
        writer.write(CompilationStarted(compilerName(projectName, inTest), flowId))
      }
    }
  }

  private def compilationGeneration(flowId: String): AtomicLong =
    compilationFlowGenerations.computeIfAbsent(flowId, _ => new AtomicLong())

  private def flushCompilerSummary(flowId: String): Unit = {
    val counts = compilerProblemCounts.remove(flowId)
    if (counts != null) {
      counts.warningSummary.foreach(summary => log(_root_.sbt.Level.Warn, summary, flowId))
      counts.errorSummary.foreach(summary => log(_root_.sbt.Level.Error, summary, flowId))
    }
  }

  private def compilerName(projectName: Option[String], inTest: Boolean): String = {
    val configurationName = if (inTest) s"$CompilerDisplayName in Test" else CompilerDisplayName
    projectName.fold(configurationName)(name => s"$configurationName [$name]")
  }

  private def withLevelPrefix(level: String, text: String): String = {
    val prefix = s"[${level.toLowerCase}] "
    text.split("\\r?\\n", -1).map(prefix + _).mkString("\n")
  }
}

object SbtBuildEventReporter {
  private final class CompilerProblemCounts {
    private var warnings = 0
    private var errors = 0

    def record(severity: xsbti.Severity): Unit = synchronized {
      severity match {
        case xsbti.Severity.Warn => warnings += 1
        case xsbti.Severity.Error => errors += 1
        case _ =>
      }
    }

    def warningSummary: Option[String] = synchronized {
      if (warnings > 0) Some(countElementsAsString(warnings, "warning") + " found") else None
    }

    def errorSummary: Option[String] = synchronized {
      if (errors > 0) Some(countElementsAsString(errors, "error") + " found") else None
    }

    private def countElementsAsString(count: Int, noun: String): String = {
      val quantity = count match {
        case 1 => "one"
        case 2 => "two"
        case 3 => "three"
        case 4 => "four"
        case _ => count.toString
      }
      s"$quantity $noun${if (count == 1) "" else "s"}"
    }
  }
}
