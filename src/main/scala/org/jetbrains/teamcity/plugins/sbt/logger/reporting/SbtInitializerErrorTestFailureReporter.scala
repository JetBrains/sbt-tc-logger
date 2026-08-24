// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

/** Publishes the structured test failure embedded in SBT's initializer-error task output. */
final class SbtInitializerErrorTestFailureReporter(testReportListener: SbtTestReportListener) {
  def reportIfInitializerError(message: String, flowId: String): Unit = {
    val suffix = "java.lang.ExceptionInInitializerError"
    if (message.indexOf(suffix) > -1) {
      suiteName(message).foreach { name =>
        testReportListener.finishInitializerFailure(name, message, flowId)
      }
    }
  }

  private def suiteName(message: String): Option[String] = {
    val prefix = "Could not run test"
    val prefixIndex = message.indexOf(prefix)
    if (prefixIndex > -1) {
      val name = message.substring(prefixIndex + prefix.length, message.indexOf("java.lang.ExceptionInInitializerError"))
        .trim()
        .stripSuffix(":")
        .trim()
      if (name.nonEmpty) Some(name) else None
    } else {
      "(?m)(?:^|\\R)(?:\\[error\\]\\s*)?\\s*at ([A-Za-z0-9_$.]+)\\.<init>\\(".r
        .findFirstMatchIn(message)
        .map(_.group(1))
    }
  }
}
