package jetbrains.buildServer

import sbt._
import sbt.testing.OptionalThrowable



trait LogAppender {

  def log(level: sbt.Level.Value, message: => String, flowId: String)

  def compilationBlockStart()

  def compilationBlockEnd()

  def compilationTestBlockStart()

  def compilationTestBlockEnd()

  def testSuitStart(name: String, flowId: String)

  def testSuitSuccessfulResult(name: String, flowId: String)
  
  def testSuitFailResult(name: String, t: Throwable, flowId: String)

  def testStart(name: String, flowId: String)

  def testFinished(name: String, status: String, duration: Long, flowId: String)

  def testFailed(name: String, details: String, flowId: String)

  def testSkipped(name: String, flowId: String)

  def testCancelled(name: String, flowId: String)

}