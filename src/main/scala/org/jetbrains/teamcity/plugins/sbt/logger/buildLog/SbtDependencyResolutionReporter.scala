// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import java.net.URI

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage._
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter

import scala.collection.mutable

/**
 * Owns the single Build Log block representing one concurrent Coursier dependency-resolution wave.
 *
 * SBT can run updates for several project/configuration pairs at once. This reporter deliberately keeps one shared
 * TeamCity block and prefixes its messages with the originating pair instead of creating noisy nested blocks.
 */
final class SbtDependencyResolutionReporter(writer: TeamCityServiceMessageWriter) {
  import SbtDependencyResolutionReporter.DownloadState

  private val FlowId = "teamcity-sbt-dependency-resolution"
  private val downloads = mutable.Map.empty[(String, String, String), DownloadState]
  private var activeUpdates = 0
  private var startedAtNanos = 0L
  private var localCacheHits = 0
  private var completedDownloads = 0
  private var failedDownloads = 0
  private var updateReportCacheHits = 0

  def started(): Unit = synchronized {
    if (activeUpdates == 0) {
      startedAtNanos = System.nanoTime()
      localCacheHits = 0
      completedDownloads = 0
      failedDownloads = 0
      updateReportCacheHits = 0
      downloads.clear()
      writer.write(BlockOpened("Dependency resolution", FlowId))
    }
    activeUpdates += 1
  }

  def finished(): Unit = synchronized {
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
      writer.write(BuildLogMessage(
        BuildLogStatus.Normal,
        s"Dependency resolution finished in ${formatDuration(elapsedMillis)}: $counts",
        Some(FlowId)
      ))
      writer.write(BlockClosed("Dependency resolution", FlowId))
      downloads.clear()
    }
  }

  def reportCacheHit(projectName: String, configuration: String): Unit = synchronized {
    if (isActive) {
      updateReportCacheHits += 1
      message(BuildLogStatus.Normal, projectName, configuration, "sbt update report cache hit")
    }
  }

  def foundLocally(projectName: String, configuration: String, url: String): Unit = synchronized {
    if (isActive) {
      localCacheHits += 1
      downloads.remove(downloadKey(projectName, configuration, url))
      message(BuildLogStatus.Normal, projectName, configuration, s"local cache hit ${sanitiseUrl(url)}")
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
        message(BuildLogStatus.Normal, projectName, configuration, s"downloaded ${sanitiseUrl(url)} (${formatSize(state.totalLength)}, $duration)")
      } else {
        failedDownloads += 1
        // A failed Coursier URL can be a normal fallback before a later resolver succeeds.
        message(BuildLogStatus.Warning, projectName, configuration, s"failed download attempt ${sanitiseUrl(url)} (after $duration)")
      }
    }
  }

  private def isActive: Boolean = activeUpdates > 0

  private def message(status: BuildLogStatus, projectName: String, configuration: String, text: String): Unit =
    writer.write(BuildLogMessage(status, s"[$projectName / $configuration] $text", Some(FlowId)))

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
}

private object SbtDependencyResolutionReporter {
  final case class DownloadState(startedAtNanos: Long, totalLength: Option[Long])
}
