package jetbrains.buildServer.sbtlogger

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test
import sbt.internal.util.ObjectEvent
import sbt.util.Level
import sbt.jetbrains.buildServer.sbtlogger.TCLoggerAppender

class TCLoggerAppenderTest {

  @Test
  def concurrentFirstEventsWaitUntilCompilationStartCompletes(): Unit = {
    val startEntered = new CountDownLatch(1)
    val releaseStart = new CountDownLatch(1)
    val messageLogged = new CountDownLatch(1)
    val delegate = new NoOpLogAppender {
      override def logCompilerTask(level: Level.Value, message: => String, compilerFlowId: String): Unit =
        messageLogged.countDown()
    }
    val appender = new TCLoggerAppender(
      delegate,
      "compiler-flow",
      isCompilerTask = true,
      Some(() => {
        startEntered.countDown()
        releaseStart.await(10, TimeUnit.SECONDS)
        ()
      })
    )

    val first = new Thread(() => appender.appendLog(Level.Info, "first"))
    val second = new Thread(() => appender.appendLog(Level.Info, "second"))

    first.start()
    assertTrue("the first event should invoke the compilation start callback", startEntered.await(10, TimeUnit.SECONDS))
    second.start()

    assertFalse("no compiler event may overtake compilationStarted", messageLogged.await(200, TimeUnit.MILLISECONDS))
    releaseStart.countDown()
    assertTrue("compiler events should resume after compilationStarted", messageLogged.await(10, TimeUnit.SECONDS))

    first.join(10000)
    second.join(10000)
    assertFalse("the first logging thread should finish", first.isAlive)
    assertFalse("the second logging thread should finish", second.isAlive)
  }

  @Test
  def preservesTheThreeArgumentConstructor(): Unit = {
    new TCLoggerAppender(new NoOpLogAppender, "compiler-flow", isCompilerTask = true)
  }

  @Test
  def objectEventDetailsAreDisabledByDefault(): Unit = {
    withObjectEventDetailsOption(None) {
      assertEquals("event payload", renderedObjectEvent)
    }
  }

  @Test
  def objectEventDetailsAreAppendedWhenEnabled(): Unit = {
    withObjectEventDetailsOption(Some("true")) {
      assertEquals(
        "event payload (ObjectEvent details: channelName=Some(channel), execId=Some(execution), contentType=plain)",
        renderedObjectEvent
      )
    }
  }

  private def renderedObjectEvent: String = {
    val delegate = new CapturingLogAppender
    val appender = new TCLoggerAppender(delegate, "flow", isCompilerTask = false)
    val event = new ObjectEvent[String](
      Level.Info,
      "event payload",
      Some("channel"),
      Some("execution"),
      "plain",
      null
    )
    appender.appendObjectEvent(Level.Info, event)
    delegate.loggedMessage
  }

  private def withObjectEventDetailsOption(value: Option[String])(body: => Unit): Unit = {
    val property = "teamcity.sbt.logger.renderObjectEventDetails"
    val previousValue = Option(System.getProperty(property))
    try {
      value match {
        case Some(currentValue) => System.setProperty(property, currentValue)
        case None => System.clearProperty(property)
      }
      body
    } finally {
      previousValue match {
        case Some(previous) => System.setProperty(property, previous)
        case None => System.clearProperty(property)
      }
    }
  }

  private class NoOpLogAppender extends LogAppender {
    override def log(level: Level.Value, message: => String, flowId: String): Unit = ()
    override def log(level: String, message: => String, flowId: String): Unit = ()
    override def logCompilerTask(level: Level.Value, message: => String, compilerFlowId: String): Unit = ()
    override def compilationBlockStart(flowId: String, projectName: Option[String]): Unit = ()
    override def compilationBlockEnd(flowId: String, projectName: Option[String]): Unit = ()
    override def compilationTestBlockStart(flowId: String, projectName: Option[String]): Unit = ()
    override def compilationTestBlockEnd(flowId: String, projectName: Option[String]): Unit = ()
    override def testSuiteStart(name: String, flowId: String): Unit = ()
    override def testSuiteSuccessfulResult(name: String, flowId: String): Unit = ()
    override def testSuiteFailResult(name: String, t: Throwable, flowId: String): Unit = ()
    override def testStart(name: String, flowId: String): Unit = ()
    override def testFinished(name: String, status: String, duration: Long, flowId: String): Unit = ()
    override def testFailed(name: String, details: String, flowId: String): Unit = ()
    override def testSkipped(name: String, flowId: String): Unit = ()
    override def testCancelled(name: String, flowId: String): Unit = ()
  }

  private class CapturingLogAppender extends NoOpLogAppender {
    var loggedMessage: String = _

    override def log(level: Level.Value, message: => String, flowId: String): Unit =
      loggedMessage = message
  }
}
