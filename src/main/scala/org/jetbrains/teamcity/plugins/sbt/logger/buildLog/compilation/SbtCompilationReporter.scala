// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog.compilation

import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildLogMessageReporter
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.{CompilationFinished, CompilationStarted}
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import sbt.util.Level

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Coordinates compiler lifecycle, flow routing, and diagnostic summaries for concurrent SBT compilations. */
final class SbtCompilationReporter(
  writer: TeamCityServiceMessageWriter,
  buildLogMessageReporter: SbtBuildLogMessageReporter
) {
  private val activeFlows = ConcurrentHashMap.newKeySet[String]()
  private val flowGenerations = new ConcurrentHashMap[String, AtomicLong]()
  private val problemCounts = new ConcurrentHashMap[String, CompilerProblemCounts]()

  /** Routes compiler-task output to a compilation flow only after that flow has started. */
  def logCompilerMessage(level: Level.Value, message: => String, compilerFlowId: String): Unit = {
    val text = message
    if (activeFlows.contains(compilerFlowId))
      buildLogMessageReporter.log(level, text, compilerFlowId)
    else
      buildLogMessageReporter.logUngrouped(level, text)
  }

  def reportCompilerProblem(flow: SbtCompilationFlow, severity: xsbti.Severity, message: => String): Unit = {
    val counts = problemCounts.computeIfAbsent(flow.flowId, _ => new CompilerProblemCounts)
    counts.record(severity)
    buildLogMessageReporter.log(sbtLevel(severity), message, flow.flowId)
  }

  def started(flow: SbtCompilationFlow): Unit = {
    val generation = flowGeneration(flow.flowId)
    generation.synchronized {
      if (activeFlows.add(flow.flowId)) {
        problemCounts.remove(flow.flowId)
        writer.write(CompilationStarted(flow.displayName, flow.flowId))
      }
    }
  }

  def finished(flow: SbtCompilationFlow): Unit = {
    val generation = flowGeneration(flow.flowId)
    generation.synchronized {
      if (activeFlows.remove(flow.flowId)) {
        flushProblemSummary(flow.flowId)
        writer.write(CompilationFinished(flow.displayName, flow.flowId))
      }
      generation.incrementAndGet()
    }
  }

  /** Returns a start action that cannot resurrect a compilation after its task has finished. */
  def guardedStart(flow: SbtCompilationFlow): () => Unit = {
    val generation = flowGeneration(flow.flowId)
    val expectedGeneration = generation.get()
    () => generation.synchronized {
      if (generation.get() == expectedGeneration && activeFlows.add(flow.flowId)) {
        problemCounts.remove(flow.flowId)
        writer.write(CompilationStarted(flow.displayName, flow.flowId))
      }
    }
  }

  private def flowGeneration(flowId: String): AtomicLong =
    flowGenerations.computeIfAbsent(flowId, _ => new AtomicLong())

  private def flushProblemSummary(flowId: String): Unit = {
    val counts = problemCounts.remove(flowId)
    if (counts != null) {
      counts.warningSummary.foreach { summary =>
        buildLogMessageReporter.log(Level.Warn, summary, flowId)
      }
      counts.errorSummary.foreach { summary =>
        buildLogMessageReporter.log(Level.Error, summary, flowId)
      }
    }
  }

  private def sbtLevel(severity: xsbti.Severity): Level.Value = {
    import xsbti.Severity.*
    severity match {
      case Info => Level.Info
      case Warn => Level.Warn
      case Error => Level.Error
    }
  }
}
