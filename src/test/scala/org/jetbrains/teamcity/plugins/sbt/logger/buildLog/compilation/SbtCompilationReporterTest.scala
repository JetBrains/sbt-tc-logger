package org.jetbrains.teamcity.plugins.sbt.logger.buildLog.compilation

import scala.collection.mutable

import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildLogMessageReporter
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.*
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.{TeamCityServiceMessage, TeamCityServiceMessageWriter}
import org.junit.Assert.assertEquals
import org.junit.Test
import sbt.util.Level

class SbtCompilationReporterTest {
  @Test
  def reportsSupplementaryTotalsForEveryRoutedWarningAndError(): Unit = {
    val writer = new CapturingWriter
    val reporter = new SbtCompilationReporter(writer, new SbtBuildLogMessageReporter(writer))
    val flow = SbtCompilationFlow("compile-flow", Some("core"), SbtCompilationConfiguration.Main)

    reporter.logCompilerMessage(Level.Info, "before start", flow.flowId)
    reporter.started(flow)
    reporter.logCompilerMessage(Level.Info, "during compilation", flow.flowId)
    reporter.reportCompilerProblem(flow, xsbti.Severity.Warn, "warning one")
    reporter.reportCompilerProblem(flow, xsbti.Severity.Warn, "warning two")
    reporter.reportCompilerProblem(flow, xsbti.Severity.Error, "error one")
    reporter.reportCompilerProblem(flow, xsbti.Severity.Error, "error two")
    reporter.reportCompilerProblem(flow, xsbti.Severity.Info, "information")
    reporter.finished(flow)

    assertEquals(Seq(
      BuildLogMessage(BuildLogStatus.Normal, "[info] before start", None),
      CompilationStarted("Scala compiler [core]", "compile-flow"),
      BuildLogMessage(BuildLogStatus.Normal, "[info] during compilation", Some("compile-flow")),
      BuildLogMessage(BuildLogStatus.Warning, "[warn] warning one", Some("compile-flow")),
      BuildLogMessage(BuildLogStatus.Warning, "[warn] warning two", Some("compile-flow")),
      BuildLogMessage(BuildLogStatus.Error, "[error] error one", Some("compile-flow")),
      BuildLogMessage(BuildLogStatus.Error, "[error] error two", Some("compile-flow")),
      BuildLogMessage(BuildLogStatus.Normal, "[info] information", Some("compile-flow")),
      BuildLogMessage(BuildLogStatus.Warning, "[warn] two warnings found", Some("compile-flow")),
      BuildLogMessage(BuildLogStatus.Error, "[error] two errors found", Some("compile-flow")),
      CompilationFinished("Scala compiler [core]", "compile-flow")
    ), writer.messages.toSeq)
  }

  @Test
  def doesNotEmitATotalForInformationOnlyFlow(): Unit = {
    val writer = new CapturingWriter
    val reporter = new SbtCompilationReporter(writer, new SbtBuildLogMessageReporter(writer))
    val flow = SbtCompilationFlow("compile-flow", Some("core"), SbtCompilationConfiguration.Main)

    reporter.started(flow)
    reporter.reportCompilerProblem(flow, xsbti.Severity.Info, "information")
    reporter.finished(flow)

    assertEquals(Seq(
      CompilationStarted("Scala compiler [core]", "compile-flow"),
      BuildLogMessage(BuildLogStatus.Normal, "[info] information", Some("compile-flow")),
      CompilationFinished("Scala compiler [core]", "compile-flow")
    ), writer.messages.toSeq)
  }

  @Test
  def doesNotLetAnOldGuardedStartReopenAFinishedCompilation(): Unit = {
    val writer = new CapturingWriter
    val reporter = new SbtCompilationReporter(writer, new SbtBuildLogMessageReporter(writer))
    val flow = SbtCompilationFlow("test-flow", Some("core"), SbtCompilationConfiguration.Test)
    val staleStart = reporter.guardedStart(flow)

    reporter.started(flow)
    reporter.finished(flow)
    staleStart()

    assertEquals(Seq(
      CompilationStarted("Scala compiler in Test [core]", "test-flow"),
      CompilationFinished("Scala compiler in Test [core]", "test-flow")
    ), writer.messages.toSeq)
  }

  private final class CapturingWriter extends TeamCityServiceMessageWriter {
    val messages: mutable.ArrayBuffer[TeamCityServiceMessage] = mutable.ArrayBuffer.empty

    override def write(message: TeamCityServiceMessage): Unit = messages += message
  }
}
