// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages

/** Writes typed TeamCity service messages to a destination. */
trait TeamCityServiceMessageWriter {
  def write(message: TeamCityServiceMessage): Unit
}
