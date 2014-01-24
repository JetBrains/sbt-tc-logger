package jetbrains.buildServer

import sbt._
import jetbrains.buildServer.messages.serviceMessages.MapSerializerUtil

class TCLogAppender extends LogAppender {

  def log(level: sbt.Level.Value, message: => String, flowId: String) = {
    val status = discoverStatus(level)
    val escaped = MapSerializerUtil.escapeStr(message, MapSerializerUtil.STD_ESCAPER2)
    println(s"##teamcity[message status='$status' flowId='$flowId' text='$escaped']")

  }

  def discoverStatus(level: sbt.Level.Value): String = {
    val status = level match {
      case Level.Error => "ERROR"
      case Level.Warn => "WARNING"
      case other => "NORMAL"
    }
    status
  }

  def compilationBlockStart() {
    println(s"##teamcity[compilationStarted compiler='Scala compiler']")
  }

  def compilationTestBlockStart() {
      println(s"##teamcity[compilationStarted compiler='Scala compiler (in tests)']")
  }

  def compilationBlockEnd() {
      println(s"##teamcity[compilationFinished compiler='Scala compiler']")
  }

  def compilationTestBlockEnd() {
      println(s"##teamcity[compilationFinished compiler='Scala compiler (in tests)']")
  }


  def testSuitStart(name: String, flowId: String) {
    println(s"##teamcity[testSuiteStarted name='$name' flowId='$flowId']")
  }

  def testOccurred(name: String, status: String, duration: Long, flowId: String) {
    println(s"##teamcity[testStarted name='$name' captureStandardOutput='true' flowId='$flowId']")
    println(s"##teamcity[testFinished name='$name' duration='$duration' flowId='$flowId']")
  }

  def testSuitSuccessfulResult(name: String, flowId: String) {
    println(s"##teamcity[testSuiteFinished name='$name' flowId='$flowId']")
  }

  def testSuitFailResult(name: String, t: Throwable, flowId: String) {
    val message = MapSerializerUtil.escapeStr(t.getMessage,MapSerializerUtil.STD_ESCAPER2)
    val details = t.getStackTrace
    println(s"##teamcity[testSuiteFinished name='$name' message='$message' details='$details' flowId='$flowId']")
  }
}
