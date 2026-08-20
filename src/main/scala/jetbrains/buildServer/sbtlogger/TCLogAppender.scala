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

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable

class TCLogAppender extends LogAppender {

  val CompilerName = "Scala compiler"

  private val activeCompilationFlows = ConcurrentHashMap.newKeySet[String]()
  private val compilationFlowGenerations = new ConcurrentHashMap[String, AtomicLong]()
  private val compilerProblemCounts = new ConcurrentHashMap[String, CompilerProblemCounts]()
  private val detailedDependencyReporter = new DetailedDependencyReporter

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

  /**
   * A resolution wave is shared by every concurrently running `update` task.  The callers deliberately do not
   * create per-project TeamCity blocks: the project/configuration prefix on individual lines keeps the one group
   * useful without making a large multi-module build log noisy.
   */
  def detailedDependencyResolutionStarted(projectName: String, configuration: String): Unit =
    detailedDependencyReporter.started(projectName, configuration)

  def detailedDependencyResolutionFinished(projectName: String, configuration: String): Unit =
    detailedDependencyReporter.finished(projectName, configuration)

  def detailedDependencyReportCacheHit(projectName: String, configuration: String): Unit =
    detailedDependencyReporter.reportCacheHit(projectName, configuration)

  def detailedDependencyFoundLocally(projectName: String, configuration: String, url: String): Unit =
    detailedDependencyReporter.foundLocally(projectName, configuration, url)

  def detailedDependencyDownloading(projectName: String, configuration: String, url: String): Unit =
    detailedDependencyReporter.downloading(projectName, configuration, url)

  def detailedDependencyDownloadLength(projectName: String, configuration: String, url: String, totalLength: Long): Unit =
    detailedDependencyReporter.downloadLength(projectName, configuration, url, totalLength)

  def detailedDependencyDownloaded(projectName: String, configuration: String, url: String, success: Boolean): Unit =
    detailedDependencyReporter.downloaded(projectName, configuration, url, success)

  private[sbtlogger] def compilationBlockStartCallback(flowId: String, projectName: Option[String]): () => Unit =
    guardedCompilationStart(flowId, projectName, inTest = false)

  private[sbtlogger] def compilationTestBlockStartCallback(flowId: String, projectName: Option[String]): () => Unit =
    guardedCompilationStart(flowId, projectName, inTest = true)

  def compilationBlockStart(flowId: String, projectName: Option[String]): Unit = {
    val generation = compilationGeneration(flowId)
    generation.synchronized {
      startCompilationBlock(flowId, projectName, inTest = false)
    }
  }

  def compilationBlockEnd(flowId: String, projectName: Option[String]): Unit = {
    val generation = compilationGeneration(flowId)
    generation.synchronized {
      finishCompilationBlock(flowId, projectName, inTest = false)
      generation.incrementAndGet()
    }
  }

  def compilationTestBlockStart(flowId: String, projectName: Option[String]): Unit = {
    val generation = compilationGeneration(flowId)
    generation.synchronized {
      startCompilationBlock(flowId, projectName, inTest = true)
    }
  }

  def compilationTestBlockEnd(flowId: String, projectName: Option[String]): Unit = {
    val generation = compilationGeneration(flowId)
    generation.synchronized {
      finishCompilationBlock(flowId, projectName, inTest = true)
      generation.incrementAndGet()
    }
  }

  private def guardedCompilationStart(
    flowId: String,
    projectName: Option[String],
    inTest: Boolean
  ): () => Unit = {
    val generation = compilationGeneration(flowId)
    val expectedGeneration = generation.get()
    () => generation.synchronized {
      if (generation.get() == expectedGeneration) startCompilationBlock(flowId, projectName, inTest)
    }
  }

  private def compilationGeneration(flowId: String): AtomicLong =
    compilationFlowGenerations.computeIfAbsent(flowId, _ => new AtomicLong())

  private def startCompilationBlock(flowId: String, projectName: Option[String], inTest: Boolean): Unit = {
    if (activeCompilationFlows.add(flowId)) {
      compilerProblemCounts.remove(flowId)
      printServerMessage("compilationStarted", "compiler" -> compilerName(projectName, inTest), "flowId" -> flowId)
    }
  }

  private def finishCompilationBlock(flowId: String, projectName: Option[String], inTest: Boolean): Unit = {
    if (activeCompilationFlows.remove(flowId)) {
      flushCompilerSummary(flowId)
      printServerMessage("compilationFinished", "compiler" -> compilerName(projectName, inTest), "flowId" -> flowId)
    }
  }

  private def logUngrouped(level: sbt.Level.Value, text: String): Unit = {
    val status = discoverStatus(level)
    printServerMessage("message", "status" -> status, "text" -> withLevelPrefix(level.toString, text))
  }

  private def flushCompilerSummary(flowId: String): Unit = {
    val counts = compilerProblemCounts.remove(flowId)
    if (counts != null) {
      counts.warningSummary.foreach(summary => log(sbt.Level.Warn, summary, flowId))
      counts.errorSummary.foreach(summary => log(sbt.Level.Error, summary, flowId))
    }
  }

  private def compilerName(projectName: Option[String], inTest: Boolean = false): String = {
    val configurationName = if (inTest) s"$CompilerName in Test" else CompilerName
    projectName.fold(configurationName)(name => s"$configurationName [$name]")
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

  /** Owns the one TeamCity block that represents an entire concurrent Coursier update wave. */
  private final class DetailedDependencyReporter {
    private val FlowId = "teamcity-sbt-dependency-resolution"
    private val downloads = mutable.Map.empty[(String, String, String), DownloadState]
    private var activeUpdates = 0
    private var startedAtNanos = 0L
    private var localCacheHits = 0
    private var completedDownloads = 0
    private var failedDownloads = 0
    private var updateReportCacheHits = 0

    def started(projectName: String, configuration: String): Unit = synchronized {
      if (activeUpdates == 0) {
        startedAtNanos = System.nanoTime()
        localCacheHits = 0
        completedDownloads = 0
        failedDownloads = 0
        updateReportCacheHits = 0
        downloads.clear()
        printServerMessage("blockOpened", "name" -> "Dependency resolution", "flowId" -> FlowId)
      }
      activeUpdates += 1
    }

    def finished(projectName: String, configuration: String): Unit = synchronized {
      if (activeUpdates <= 0) return
      activeUpdates -= 1
      if (activeUpdates == 0) {
        val elapsedMillis = elapsedMillisSince(startedAtNanos)
        val counts = Seq(
          countText(localCacheHits, "local cache hit"),
          countText(completedDownloads, "download"),
          countText(failedDownloads, "failed download attempt"),
          countText(updateReportCacheHits, "sbt update report cache hit")
        ).mkString(", ")
        printServerMessage(
          "message",
          "status" -> "NORMAL",
          "flowId" -> FlowId,
          "text" -> s"Dependency resolution finished in ${formatDuration(elapsedMillis)}: $counts"
        )
        printServerMessage("blockClosed", "name" -> "Dependency resolution", "flowId" -> FlowId)
        downloads.clear()
      }
    }

    def reportCacheHit(projectName: String, configuration: String): Unit = synchronized {
      if (isActive) {
        updateReportCacheHits += 1
        message("NORMAL", projectName, configuration, "sbt update report cache hit")
      }
    }

    def foundLocally(projectName: String, configuration: String, url: String): Unit = synchronized {
      if (isActive) {
        localCacheHits += 1
        downloads.remove(downloadKey(projectName, configuration, url))
        message("NORMAL", projectName, configuration, s"local cache hit ${sanitiseUrl(url)}")
      }
    }

    def downloading(projectName: String, configuration: String, url: String): Unit = synchronized {
      if (isActive) downloads.update(downloadKey(projectName, configuration, url), DownloadState(System.nanoTime(), None))
    }

    def downloadLength(projectName: String, configuration: String, url: String, totalLength: Long): Unit = synchronized {
      if (isActive && totalLength >= 0) {
        val key = downloadKey(projectName, configuration, url)
        val previous = downloads.getOrElse(key, DownloadState(System.nanoTime(), None))
        downloads.update(key, previous.copy(totalLength = Some(totalLength)))
      }
    }

    def downloaded(projectName: String, configuration: String, url: String, success: Boolean): Unit = synchronized {
      if (isActive) {
        val state = downloads.remove(downloadKey(projectName, configuration, url)).getOrElse(DownloadState(System.nanoTime(), None))
        val duration = formatDuration(elapsedMillisSince(state.startedAtNanos))
        if (success) {
          completedDownloads += 1
          message("NORMAL", projectName, configuration, s"downloaded ${sanitiseUrl(url)} (${formatSize(state.totalLength)}, $duration)")
        } else {
          failedDownloads += 1
          // A failed Coursier URL can be a normal fallback before a later resolver succeeds; never mark the build as
          // an error here. sbt will issue its own final error if no resolver can provide the artifact.
          message("WARNING", projectName, configuration, s"failed download attempt ${sanitiseUrl(url)} (after $duration)")
        }
      }
    }

    private def isActive: Boolean = activeUpdates > 0

    private def message(status: String, projectName: String, configuration: String, text: String): Unit =
      printServerMessage(
        "message",
        "status" -> status,
        "flowId" -> FlowId,
        "text" -> s"[$projectName / $configuration] $text"
      )

    private def downloadKey(projectName: String, configuration: String, url: String): (String, String, String) =
      (projectName, configuration, url)

    private def elapsedMillisSince(nanos: Long): Long =
      math.max(0L, (System.nanoTime() - nanos) / 1000000L)

    private def formatDuration(millis: Long): String =
      if (millis < 1000L) s"${millis} ms"
      else java.lang.String.format(java.util.Locale.ROOT, "%.2f s", Double.box(millis / 1000.0))

    private def formatSize(size: Option[Long]): String = size match {
      case Some(bytes) if bytes < 1024L => s"$bytes B"
      case Some(bytes) if bytes < 1024L * 1024L =>
        java.lang.String.format(java.util.Locale.ROOT, "%.1f KiB", Double.box(bytes / 1024.0))
      case Some(bytes) =>
        java.lang.String.format(java.util.Locale.ROOT, "%.1f MiB", Double.box(bytes / (1024.0 * 1024.0)))
      case None => "size unknown"
    }

    private def countText(count: Int, noun: String): String =
      s"$count $noun${if (count == 1) "" else "s"}"

    private def sanitiseUrl(url: String): String = {
      try {
        val parsed = new URI(url)
        val authority = Option(parsed.getRawAuthority).map { value =>
          val userInfoSeparator = value.lastIndexOf('@')
          if (userInfoSeparator >= 0) value.substring(userInfoSeparator + 1) else value
        }
        val scheme = Option(parsed.getScheme).map(_ + ":").getOrElse("")
        scheme + authority.map("//" + _).getOrElse("") + Option(parsed.getRawPath).getOrElse("")
      } catch {
        case _: Exception =>
          val withoutFragmentOrQuery = url.takeWhile(character => character != '?' && character != '#')
          withoutFragmentOrQuery.replaceFirst("(?<=//)[^/@]*@", "")
      }
    }

    private final case class DownloadState(startedAtNanos: Long, totalLength: Option[Long])
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
