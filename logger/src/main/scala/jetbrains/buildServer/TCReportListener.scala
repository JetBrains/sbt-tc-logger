package jetbrains.buildServer

import java.util.concurrent.atomic.AtomicInteger

import sbt._
import testing.{Logger => TLogger, Event => TEvent, Status, OptionalThrowable}
import java.io.{PrintWriter, StringWriter}


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
      val selector = event.selector
      val status = event.status.toString
      val duration = event.duration
      val throwable = event.throwable
      val testName = s"$name.$selector"

      appender.testStart(s"$testName", flowId)

      event.status match {
        case Status.Success => // nothing extra to report
        case Status.Error | Status.Failure =>
          appender.testFailed(name,formattedException(throwable),flowId)
        case Status.Skipped | Status.Ignored | Status.Pending=>
          appender.testSkipped(name,flowId)
        case Status.Canceled =>
          appender.testSkipped(name,flowId)
      }

      appender.testFinished(s"$testName", status, duration, flowId)
  	}

  /** called if there was an error during test */
  def endGroup(name: String, t: Throwable) {
    appender.testSuitFailResult(name, t, flowId)
  }

  /** called if test completed */
  def endGroup(name: String, result: TestResult.Value) {
    println("endGroup. name:= " + name + "result:=" + result)
    appender.testSuitSuccessfulResult(name, flowId)
  }


}
