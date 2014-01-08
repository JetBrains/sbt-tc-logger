package jetbrains.buildServer

import sbt._

class TCReportListener(ap: LogAppender)  extends TestReportListener{

  val appender: LogAppender = ap

  def startGroup(name: String) {
    appender.testSuitStart(name,"" + Thread.currentThread().getId)
  }

  /** called for each test method or equivalent */
  def testEvent(event: TestEvent){
    println("testEvent. event:= " + event)
  }

  /** called if there was an error during test */
  def endGroup(name: String, t: Throwable){
    appender.testSuitFailResult(name,t,"" + Thread.currentThread().getId)
  }

  /** called if test completed */
  def endGroup(name: String, result: TestResult.Value){
    println("endGroup. name:= " + name + "result:=" + result)
    appender.testSuitStart(name,"" + Thread.currentThread().getId)
  }



}
