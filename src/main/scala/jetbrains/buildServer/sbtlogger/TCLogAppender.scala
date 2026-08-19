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

import java.util.concurrent.ConcurrentHashMap

class TCLogAppender extends LogAppender {

  val CompilerName = "Scala compiler"

  private val activeDependencyFlows = ConcurrentHashMap.newKeySet[String]()
  private val directDependencyFlows = ConcurrentHashMap.newKeySet[String]()
  private val activeCompilationFlows = ConcurrentHashMap.newKeySet[String]()
  private val compilerProblemCounts = new ConcurrentHashMap[String, CompilerProblemCounts]()

  def log(level: sbt.Level.Value, message: => String, flowId: String): Unit = {
    val text = message
    val status = discoverStatus(level)

    if (sbt.Level.Error.equals(level)){
      processSpecialErrorsMessage(text, flowId)
    }

    printServerMessage("message", "status" -> status, "flowId" -> flowId, "text" -> withLevelPrefix(level.toString, text))
  }

  def log(level: String, message: => String, flowId: String): Unit = {
    val text = message
    val status = discoverStatus(level)

    if ("ERROR".equals(status)){
      processSpecialErrorsMessage(text, flowId)
    }

    printServerMessage("message", "status" -> status, "flowId" -> flowId, "text" -> withLevelPrefix(level, text))
  }

  def logCompilerTask(level: sbt.Level.Value, message: => String, compilerFlowId: String): Unit = {
    val text = message
    if (activeCompilationFlows.contains(compilerFlowId)) log(level, text, compilerFlowId)
    else logUngrouped(level, text)
  }

  /**
   * The default Zinc reporter owns a typed count of warnings and errors.  The
   * TeamCity reporter replaces its formatted diagnostics, so retain that count
   * here and render the same summary only after the compiler lifecycle closes.
   */
  def recordCompilerProblem(flowId: String, problem: xsbti.Problem): Unit = {
    val counts = compilerProblemCounts.computeIfAbsent(flowId, _ => new CompilerProblemCounts)
    counts.record(problem.severity())
  }


  def discoverStatus(level: sbt.Level.Value): String = {
    val status = level match {
      case sbt.Level.Error => "ERROR"
      case sbt.Level.Warn => "WARNING"
      case _ => "NORMAL"
    }
    status
  }

  def discoverStatus(level: String): String = {
    val status = level match {
      case "ERROR" => "ERROR"
      case "WARN" => "WARNING"
      case _ => "NORMAL"
    }
    status
  }

  def processSpecialErrorsMessage(message: String, flowId: String): Unit = {
    val suffix = "java.lang.ExceptionInInitializerError"
    val prefix = "Could not run test"
    if (message.indexOf(suffix) > -1 && message.indexOf(prefix) > -1){
      def testName = message.substring(message.indexOf(prefix) + prefix.length, message.indexOf(suffix)).trim()
      testFailed(testName, message, flowId)
    }
  }

  def dependencyBlockStart(flowId: String, projectName: Option[String], inTest: Boolean): Unit = {
    if (activeDependencyFlows.add(flowId)) {
      printServerMessage("blockOpened", "name" -> dependencyName(projectName, inTest), "flowId" -> flowId)
    }
  }

  def dependencyBlockEnd(flowId: String): Unit = {
    if (activeDependencyFlows.remove(flowId)) {
      printServerMessage("blockClosed", "name" -> "Dependency resolution", "flowId" -> flowId)
    }
  }

  /**
   * `update` delegates from configuration-scoped tasks to the unconfigured
   * resolver task.  A direct `update` needs its own block, but an update reached
   * from Compile or Test must not open a second, overlapping resolver block.
   */
  def directDependencyBlockStart(flowId: String, projectName: Option[String]): Unit = {
    val projectPrefix = flowId.take(flowId.indexOf(':') + 1)
    val iterator = activeDependencyFlows.iterator()
    var hasProjectResolverActivity = false
    while (iterator.hasNext && !hasProjectResolverActivity) {
      hasProjectResolverActivity = iterator.next().startsWith(projectPrefix)
    }
    if (!hasProjectResolverActivity) {
      dependencyBlockStart(flowId, projectName, inTest = false)
      directDependencyFlows.add(flowId)
    }
  }

  def directDependencyBlockEnd(flowId: String): Unit = {
    if (directDependencyFlows.remove(flowId)) dependencyBlockEnd(flowId)
  }

  def compilationBlockStart(flowId: String, projectName: Option[String]): Unit = {
    if (activeCompilationFlows.add(flowId)) {
      compilerProblemCounts.remove(flowId)
      printServerMessage("compilationStarted", "compiler" -> compilerName(projectName), "flowId" -> flowId)
    }
  }

  def compilationBlockEnd(flowId: String, projectName: Option[String]): Unit = {
    if (activeCompilationFlows.remove(flowId)) {
      printServerMessage("compilationFinished", "compiler" -> compilerName(projectName), "flowId" ->  flowId)
      flushCompilerSummary(flowId)
    }
  }

  def compilationTestBlockStart(flowId: String, projectName: Option[String]): Unit = {
    if (activeCompilationFlows.add(flowId)) {
      compilerProblemCounts.remove(flowId)
      printServerMessage("compilationStarted", "compiler" -> compilerName(projectName, inTest = true), "flowId" -> flowId)
    }
  }

  def compilationTestBlockEnd(flowId: String, projectName: Option[String]): Unit = {
    if (activeCompilationFlows.remove(flowId)) {
      printServerMessage("compilationFinished", "compiler" -> compilerName(projectName, inTest = true), "flowId" -> flowId)
      flushCompilerSummary(flowId)
    }
  }

  private def logUngrouped(level: sbt.Level.Value, text: String): Unit = {
    val status = discoverStatus(level)
    printServerMessage("message", "status" -> status, "text" -> withLevelPrefix(level.toString, text))
  }

  private def flushCompilerSummary(flowId: String): Unit = {
    val counts = compilerProblemCounts.remove(flowId)
    if (counts != null) {
      counts.warningSummary.foreach(summary => logUngrouped(sbt.Level.Warn, summary))
      counts.errorSummary.foreach(summary => logUngrouped(sbt.Level.Error, summary))
    }
  }

  private def compilerName(projectName: Option[String], inTest: Boolean = false): String = {
    val configurationName = if (inTest) s"$CompilerName in Test" else CompilerName
    projectName.fold(configurationName)(name => s"$configurationName [$name]")
  }

  private def dependencyName(projectName: Option[String], inTest: Boolean): String = {
    val phaseName = if (inTest) "Dependency resolution in Test" else "Dependency resolution"
    projectName.fold(phaseName)(name => s"$phaseName [$name]")
  }

  private def withLevelPrefix(level: String, text: String): String = {
    val prefix = s"[${level.toLowerCase}] "
    text.split("\\r?\\n", -1).map(prefix + _).mkString("\n")
  }


  def testSuiteStart(name: String, flowId: String): Unit = {
    printServerMessage("testSuiteStarted","name" -> name, "flowId" -> flowId)
  }


  def testStart(name: String, flowId: String): Unit = {
    printServerMessage("testStarted", "name" -> name, "captureStandardOutput" -> "true", "flowId" -> flowId)
  }

  def testFinished(name: String, status: String, duration: Long, flowId: String): Unit = {
    printServerMessage("testFinished", "name" -> name, "duration" -> s"$duration", "flowId" -> flowId)
  }

  def testFailed(name: String, details: String, flowId: String): Unit = {
    printServerMessage("testFailed", "name" -> name, "details" -> details, "flowId" -> flowId)
  }

  def testSkipped(name: String, flowId: String): Unit = {
    printServerMessage("testIgnored","name" -> name, "flowId" -> flowId)
  }

  def testCancelled(name: String, flowId: String): Unit = {
    printServerMessage("message", "text" -> s"Test $name was cancelled", "flowId" -> flowId)
  }

  def testSuiteSuccessfulResult(name: String, flowId: String): Unit = {
    printServerMessage("testSuiteFinished", "name" -> name, "flowId" -> flowId)
  }

  def testSuiteFailResult(name: String, t: Throwable, flowId: String): Unit = {
    val details = t.getStackTrace
    printServerMessage("testSuiteFinished","name" -> name, "message" -> t.getMessage, "details" -> s"$details", "flowId" -> flowId)
  }

  private def printServerMessage(messageName: String, attributes: (String, String)*): Unit = {
    val attributeString = attributes.map {
      case (k, v) => s"$k='${MapSerializerUtil.escapeStr(v,MapSerializerUtil.STD_ESCAPER2)}'"
    }.mkString(" ")
    println(s"##teamcity[$messageName $attributeString]")
  }
}

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
