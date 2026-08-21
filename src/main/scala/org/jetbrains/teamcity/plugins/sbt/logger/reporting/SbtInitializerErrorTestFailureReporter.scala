// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.TestFailed
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter

/** Publishes the structured test failure embedded in SBT's initializer-error task output. */
final class SbtInitializerErrorTestFailureReporter(writer: TeamCityServiceMessageWriter) {
  def reportIfInitializerError(message: String, flowId: String): Unit = {
    val suffix = "java.lang.ExceptionInInitializerError"
    val prefix = "Could not run test"
    if (message.indexOf(suffix) > -1 && message.indexOf(prefix) > -1) {
      val testName = message.substring(
        message.indexOf(prefix) + prefix.length,
        message.indexOf(suffix)
      ).trim()
      writer.write(TestFailed(testName, message, flowId))
    }
  }
}
