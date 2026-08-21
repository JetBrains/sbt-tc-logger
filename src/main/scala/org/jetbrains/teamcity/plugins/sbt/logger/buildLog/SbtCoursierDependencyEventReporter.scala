// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import lmcoursier.definitions.CacheLogger

/** Bridges one SBT project/configuration's Coursier callbacks to the shared dependency-resolution reporter. */
private[logger] final class SbtCoursierDependencyEventReporter(
  dependencyResolutionReporter: SbtDependencyResolutionReporter,
  projectName: String,
  configuration: String
) extends CacheLogger {
  override def foundLocally(url: String): Unit =
    dependencyResolutionReporter.foundLocally(projectName, configuration, url)

  override def downloadingArtifact(url: String): Unit =
    dependencyResolutionReporter.downloading(projectName, configuration, url)

  override def downloadLength(url: String, totalLength: Long, alreadyDownloaded: Long, watching: Boolean): Unit =
    dependencyResolutionReporter.downloadLength(projectName, configuration, url, totalLength)

  override def downloadedArtifact(url: String, success: Boolean): Unit =
    dependencyResolutionReporter.downloaded(projectName, configuration, url, success)
}
