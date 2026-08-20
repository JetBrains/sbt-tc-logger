/*
 * Copyright 2013-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbt.jetbrains.buildServer.sbtlogger

import sbt.internal.util.{Appender, ConsoleAppender, ObjectEvent}
import sbt.util.{Level, LogExchange, ShowLines}
import jetbrains.buildServer.sbtlogger.{LogAppender, SbtTeamCityLoggerSettings}

import scala.Option

/**
 * Redirects ordinary SBT screen-appender events to TeamCity `message` service messages.
 *
 * Each event retains its SBT severity and receives the task's TeamCity flow ID. This lets TeamCity render the
 * message with its correct status and associate concurrent project/configuration/task output with the right flow.
 * Replacing the SBT console appender also means the log event is delivered once, as a TeamCity message, rather than
 * as both raw console output and a TeamCity-rendered line.
 *
 * [[SbtTeamCityLogger]] replaces this appender with [[TCLoggerAppender.muted]] for standard test-task keys when
 * task output is disabled. That discards only ordinary task log events: structured test lifecycle messages and a
 * separately selected configured test-result logger remain independent paths.
 */
class TCLoggerAppender(
  appender: LogAppender,
  scope: String,
  isCompilerTask: Boolean,
  compilationStart: Option[() => Unit] = None
)
  extends ConsoleAppender(s"tc-logger-$scope", TCLoggerAppender.properties, ConsoleAppender.noSuppressedMessage) {

  def this(appender: LogAppender, scope: String, isCompilerTask: Boolean) =
    this(appender, scope, isCompilerTask, None)

  private val compilationStartLock = new Object
  private var compilationStartRequested = false

  override def appendLog(level: Level.Value, message: => String): Unit = {
    val text = message
    if isCompilerTask then
      requestCompilationStart()
      appender.logCompilerTask(level, text, scope)
    else appender.log(level, text, scope)
  }

  override def appendObjectEvent[T](level: Level.Value, event: => ObjectEvent[T]): Unit = {
    val objectEvent = event
    renderObjectEvent(objectEvent).foreach { text =>
      if isCompilerTask then
        requestCompilationStart()
        appender.logCompilerTask(level, text, scope)
      else appender.log(level, text, scope)
    }
  }

  private def requestCompilationStart(): Unit =
    compilationStartLock.synchronized {
      compilationStart.foreach { start =>
        if !compilationStartRequested then
          start()
          compilationStartRequested = true
      }
    }

  /**
   * SBT 2 transports a number of log events as typed objects rather than as strings.
   * The console appender uses the event content-type to find its [[sbt.util.ShowLines]] renderer.
   * Calling `toString` on the payload loses that renderer and produces implementation identities such as
   * `sbt.Defaults$$anon$3@42e5f4b` for compiler problems.
   */
  private def renderObjectEvent(event: ObjectEvent[?]): Option[String] = {
    val renderedObject = LogExchange.stringCodec(event.contentType) match {
      // The registered renderer is associated with the event's string content type,
      // so its value type is only known dynamically at this boundary.
      case Some(renderer) =>
        ObjectEventRenderer.combine(renderer.asInstanceOf[ShowLines[Any]].showLines(event.message))
      case None =>
        Some(event.message.toString)
    }
    if SbtTeamCityLoggerSettings.RenderObjectEventDetails.isEnabled then
      renderedObject.map(_ + TCLoggerAppender.objectEventDetails(event))
    else renderedObject
  }
}

object TCLoggerAppender {
  private def objectEventDetails(event: ObjectEvent[?]): String =
    s" (ObjectEvent details: channelName=${event.channelName}, execId=${event.execId}, contentType=${event.contentType})"

  private def properties: ConsoleAppender.Properties =
    ConsoleAppender("tc-logger-properties", sbt.internal.util.ConsoleOut.NullConsoleOut).properties

  /**
   * Creates a null screen appender for a selectively muted task stream.
   *
   * No TeamCity `message` service messages are emitted for events sent to this appender. Other TeamCity reporting
   * paths, including structured test lifecycle events, are unaffected.
   */
  def muted(kind: String): Appender =
    ConsoleAppender(s"teamcity-muted-$kind-${System.nanoTime()}", sbt.internal.util.ConsoleOut.NullConsoleOut)
}
