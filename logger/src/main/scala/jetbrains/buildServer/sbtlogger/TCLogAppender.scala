/*
 * Copyright 2013-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.messages.serviceMessages.MapSerializerUtil
import org.apache.logging.log4j.Level

class TCLogAppender extends LogAppender {

  val CompilerName = "Scala compiler"

  def log(level: sbt.Level.Value, message: => String, flowId: String): Unit = {
    val status = discoverStatus(level)

    if (sbt.Level.Error.equals(level)){
      processSpecialErrorsMessage(message, flowId)
    }

    printServerMessage("message", "status" -> status, "flowId" -> flowId, "text" -> message)
  }

  def log(level: Level, message: String, flowId: String): Unit = {
    val status = discoverStatus(level)

    if (Level.ERROR.equals(level)){
      processSpecialErrorsMessage(message, flowId)
    }

    printServerMessage("message", "status" -> status, "flowId" -> flowId, "text" -> message)
  }

  def discoverStatus(level: sbt.Level.Value): String = {
    val status = level match {
      case sbt.Level.Error => "ERROR"
      case sbt.Level.Warn => "WARNING"
      case _ => "NORMAL"
    }
    status
  }

  def discoverStatus(level: Level): String = {
    val status = level match {
      case Level.ERROR => "ERROR"
      case Level.WARN => "WARNING"
      case _ => "NORMAL"
    }
    status
  }

  def processSpecialErrorsMessage(message: String, flowId: String) {
    val suffix = "java.lang.ExceptionInInitializerError"
    val prefix = "Could not run test"
    if (message.indexOf(suffix) > -1 && message.indexOf(prefix) > -1){
      def testName = message.substring(message.indexOf(prefix) + prefix.length, message.indexOf(suffix)).trim()
      testFailed(testName, message, flowId)
    }
  }

  def compilationBlockStart(flowId: String) {
    printServerMessage("compilationStarted", "compiler" -> CompilerName, "flowId" -> flowId)
  }

  def compilationBlockEnd(flowId: String) {
    printServerMessage("compilationFinished", "compiler" -> CompilerName, "flowId" ->  flowId)
  }

  def compilationTestBlockStart(flowId: String) {
    printServerMessage("compilationStarted", "compiler" -> s"$CompilerName in Test", "flowId" -> flowId)
  }

  def compilationTestBlockEnd(flowId: String) {
    printServerMessage("compilationFinished", "compiler" -> s"$CompilerName in Test", "flowId" -> flowId)
  }


  def testSuitStart(name: String, flowId: String) {
    printServerMessage("testSuiteStarted","name" -> name, "flowId" -> flowId)
  }


  def testStart(name: String, flowId: String){
    printServerMessage("testStarted", "name" -> name, "captureStandardOutput" -> "true", "flowId" -> flowId)
  }

  def testFinished(name: String, status: String, duration: Long, flowId: String){
    printServerMessage("testFinished", "name" -> name, "duration" -> s"$duration", "flowId" -> flowId)
  }

  def testFailed(name: String, details: String, flowId: String){
    printServerMessage("testFailed", "name" -> name, "details" -> details, "flowId" -> flowId)
  }

  def testSkipped(name: String, flowId: String){
    printServerMessage("testIgnored","name" -> name, "flowId" -> flowId)
  }

  def testCancelled(name: String, flowId: String){
    printServerMessage("message", "text" -> s"Test $name was cancelled", "flowId" -> flowId)
  }

  def testSuitSuccessfulResult(name: String, flowId: String) {
    printServerMessage("testSuiteFinished", "name" -> name, "flowId" -> flowId)
  }

  def testSuitFailResult(name: String, t: Throwable, flowId: String) {
    val details = t.getStackTrace
    printServerMessage("testSuiteFinished","name" -> name, "message" -> t.getMessage, "details" -> s"$details", "flowId" -> flowId)
  }

  private def printServerMessage(messageName: String, attributes: (String, String)*) {
    val attributeString = attributes.map {
      case (k, v) => s"$k='${MapSerializerUtil.escapeStr(v,MapSerializerUtil.STD_ESCAPER2)}'"
    }.mkString(" ")
    println(s"##teamcity[$messageName $attributeString]")
  }
  
}
