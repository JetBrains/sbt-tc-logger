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

import java.io.{PrintWriter, StringWriter}

import sbt._
import sbt.testing.{NestedTestSelector, OptionalThrowable, Status, TestSelector}
import sbt.jetbrains.apiAdapter._


class TCReportListener(ap: LogAppender) extends TestReportListener {

  val appender: LogAppender = ap

  def startGroup(name: String) {
    appender.testSuitStart(name, flowId)
  }

  /** called for each test method or equivalent */
  def testEvent(event: TestEvent) {
    event.detail.foreach(logSingleTest)
  }

  def formattedException(t: OptionalThrowable):String = {
    if (t.isDefined) {
      val w = new StringWriter
      val p = new PrintWriter(w)
      t.get.printStackTrace(p)
      w.toString
    } else ""
  }
  
  def flowId:String = {
    "" + Thread.currentThread().getId
  }
  
  protected def logSingleTest(event: sbt.testing.Event): Unit =
  	{
      val name = event.fullyQualifiedName
      val status = event.status.toString
      val duration = event.duration
      val throwable = event.throwable

      val testName = event.selector match {
          case s: TestSelector =>
            name + "." + s.testName
          case ns: NestedTestSelector =>
            name + "." + ns.suiteId + "." + ns.testName
          case _ => name
      }

      appender.testStart(s"$testName", flowId)

      event.status match {
        case Status.Success => // nothing extra to report
        case Status.Error | Status.Failure =>
          appender.testFailed(testName,formattedException(throwable),flowId)
        case Status.Skipped | Status.Ignored | Status.Pending=>
          appender.testSkipped(testName,flowId)
        case Status.Canceled =>
          appender.testSkipped(testName,flowId)
      }

      appender.testFinished(s"$testName", status, duration, flowId)
  	}

  /** called if there was an error during test */
  def endGroup(name: String, t: Throwable) {
    appender.testSuitFailResult(name, t, flowId)
  }

  /** called if test completed */
  def endGroup(name: String, result: TestResult) {
    appender.testSuitSuccessfulResult(name, flowId)
  }


}
