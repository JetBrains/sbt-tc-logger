package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.jetbrains.teamcity.plugins.sbt.logger.SbtTeamCityLoggerSettings
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessage.BuildLogMessage
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.{TeamCityServiceMessage, TeamCityServiceMessageWriter}
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test
import _root_.sbt.internal.util.ObjectEvent
import _root_.sbt.util.Level

class SbtTaskLogAppenderTest {
  @Test
  def concurrentFirstEventsWaitUntilCompilationStartCompletes(): Unit = {
    val startEntered = new CountDownLatch(1)
    val releaseStart = new CountDownLatch(1)
    val messageLogged = new CountDownLatch(1)
    val writer = new TeamCityServiceMessageWriter {
      override def write(message: TeamCityServiceMessage): Unit = messageLogged.countDown()
    }
    val appender = new SbtTaskLogAppender(
      new SbtBuildEventReporter(writer),
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
  def preservesTheThreeArgumentConstructor(): Unit =
    new SbtTaskLogAppender(new SbtBuildEventReporter(new IgnoringWriter), "compiler-flow", isCompilerTask = true)

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
    val writer = new CapturingWriter
    val appender = new SbtTaskLogAppender(new SbtBuildEventReporter(writer), "flow", isCompilerTask = false)
    val event = new ObjectEvent[String](Level.Info, "event payload", Some("channel"), Some("execution"), "plain", null)
    appender.appendObjectEvent(Level.Info, event)
    writer.loggedText
  }

  private def withObjectEventDetailsOption(value: Option[String])(body: => Unit): Unit = {
    val property = SbtTeamCityLoggerSettings.RenderObjectEventDetails.propertyName
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

  private final class IgnoringWriter extends TeamCityServiceMessageWriter {
    override def write(message: TeamCityServiceMessage): Unit = ()
  }

  private final class CapturingWriter extends TeamCityServiceMessageWriter {
    var loggedText: String = ""

    override def write(message: TeamCityServiceMessage): Unit = message match {
      case BuildLogMessage(_, text, _) => loggedText = text
      case _ =>
    }
  }
}
