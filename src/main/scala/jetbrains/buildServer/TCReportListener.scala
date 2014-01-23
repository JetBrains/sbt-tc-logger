package jetbrains.buildServer

import java.util.concurrent.atomic.AtomicInteger

import sbt._
import testing.{Logger => TLogger, Event => TEvent, Status => TStatus}


class TCReportListener(ap: LogAppender) extends TestReportListener {

  val appender: LogAppender = ap

  def startGroup(name: String) {
    appender.testSuitStart(name, "" + Thread.currentThread().getId)
  }

  /** called for each test method or equivalent */
  def testEvent(event: TestEvent) {
    event.detail.foreach(logSingleTest)
  }

  protected def logSingleTest(event: sbt.testing.Event): Unit =
  	{
      val name = event.fullyQualifiedName
      val selector = event.selector
      val status = event.status.toString
      val duration = event.duration
      appender.testOccurred(s"$name.$selector", status, duration, "" + Thread.currentThread().getId)
  	}

  /** called if there was an error during test */
  def endGroup(name: String, t: Throwable) {
    appender.testSuitFailResult(name, t, "" + Thread.currentThread().getId)
  }

  /** called if test completed */
  def endGroup(name: String, result: TestResult.Value) {
    println("endGroup. name:= " + name + "result:=" + result)
    appender.testSuitSuccessfulResult(name, "" + Thread.currentThread().getId)
  }


}
