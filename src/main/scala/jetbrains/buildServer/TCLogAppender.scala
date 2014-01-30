package jetbrains.buildServer

import sbt._
import jetbrains.buildServer.messages.serviceMessages.MapSerializerUtil

class TCLogAppender extends LogAppender {

  val CompilerName = "Scala compiler"

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
    printServerMessage("compilationStarted", Map("compiler" -> CompilerName))
  }

  def compilationBlockEnd() {
    printServerMessage("compilationFinished", Map("compiler" -> CompilerName))
  }

  def compilationTestBlockStart() {
    printServerMessage("compilationStarted", Map("compiler" -> s"$CompilerName in Test"))
  }

  def compilationTestBlockEnd() {
    printServerMessage("compilationFinished", Map("compiler" -> s"$CompilerName in Test"))
  }


  def testSuitStart(name: String, flowId: String) {
    printServerMessage("testSuiteStarted", Map("name" -> s"$name", "flowId" -> s"$flowId"))
  }

  def testOccurred(name: String, status: String, duration: Long, flowId: String) {
    printServerMessage("testStarted", Map("name" -> s"$name", "captureStandardOutput" -> "true", "flowId" -> s"$flowId"))
    printServerMessage("testFinished", Map("name" -> s"$name", "duration" -> "$duration", "flowId" -> s"$flowId"))
  }

  def testSuitSuccessfulResult(name: String, flowId: String) {
    printServerMessage("testSuiteFinished", Map("name" -> s"$name", "flowId" -> s"$flowId"))
  }

  def testSuitFailResult(name: String, t: Throwable, flowId: String) {
    val message = MapSerializerUtil.escapeStr(t.getMessage, MapSerializerUtil.STD_ESCAPER2)
    val details = t.getStackTrace
    printServerMessage("testSuiteFinished", Map("name" -> s"$name", "message" -> s"$message", "details" -> s"$details","flowId" -> s"$flowId"))

  }

  private def printServerMessage(name: String, params: Map[String, String]) {
    printf(s"##teamcity[$name")
    for ((k, v) <- params) printf(s" $k='$v'")
    println("]")
  }
}
