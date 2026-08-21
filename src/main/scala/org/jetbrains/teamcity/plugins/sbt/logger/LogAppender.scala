// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

trait LogAppender {

  def log(level: sbt.Level.Value, message: => String, flowId: String): Unit

  def log(level: String, message: => String, flowId: String): Unit

  /** Routes a compiler-task event according to the active compiler lifecycle. */
  def logCompilerTask(level: sbt.Level.Value, message: => String, compilerFlowId: String): Unit

  def compilationBlockStart(flowId: String, projectName: Option[String]): Unit

  def compilationBlockEnd(flowId: String, projectName: Option[String]): Unit

  def compilationTestBlockStart(flowId: String, projectName: Option[String]): Unit

  def compilationTestBlockEnd(flowId: String, projectName: Option[String]): Unit

  def testSuiteStart(name: String, flowId: String): Unit

  def testSuiteSuccessfulResult(name: String, flowId: String): Unit

  def testSuiteFailResult(name: String, t: Throwable, flowId: String): Unit

  def testStart(name: String, flowId: String): Unit

  def testFinished(name: String, status: String, duration: Long, flowId: String): Unit

  def testFailed(name: String, details: String, flowId: String): Unit

  def testSkipped(name: String, flowId: String): Unit

  def testCancelled(name: String, flowId: String): Unit

}
