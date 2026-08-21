// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import lmcoursier.definitions.CacheLogger

/** Adapts Coursier download callbacks to TeamCity detailed-dependency messages. */
private final class DetailedCoursierLogger(appender: TCLogAppender, projectName: String, configuration: String) extends CacheLogger {
  override def foundLocally(url: String): Unit =
    appender.detailedDependencyFoundLocally(projectName, configuration, url)

  override def downloadingArtifact(url: String): Unit =
    appender.detailedDependencyDownloading(projectName, configuration, url)

  override def downloadLength(url: String, totalLength: Long, alreadyDownloaded: Long, watching: Boolean): Unit =
    appender.detailedDependencyDownloadLength(projectName, configuration, url, totalLength)

  override def downloadedArtifact(url: String, success: Boolean): Unit =
    appender.detailedDependencyDownloaded(projectName, configuration, url, success)
}
