package jetbrains.buildServer

import sbt._


trait LogAppender {

  def log(level: sbt.Level.Value, message: => String, flowId: String)

  def compilationBlockStart()

  def compilationBlockEnd()

  def compilationTestBlockStart()

  def compilationTestBlockEnd()

  def testSuitStart(name: String, flowId: String)

  def testSuitSuccessfulResult(name: String, flowId: String)
  
  def testSuitFailResult(name: String, t: Throwable, flowId: String)

  def testOccurred(name: String, status: String, duration: Long, flowId: String)

}