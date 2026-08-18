/*
 * Copyright 2013-2021 JetBrains s.r.o.
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

package sbt.jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.LogAppender
import sbt.internal.util.{Appender, ConsoleAppender, ObjectEvent}
import sbt.util.{Level, LogExchange, ShowLines}

/** Native SBT 1.4+ appender boundary used by the shared TeamCity logger. */
class TCLoggerAppender(appender: LogAppender, flowId: String, onActivity: () => Unit)
  extends ConsoleAppender(s"tc-logger-$flowId", TCLoggerAppender.properties, ConsoleAppender.noSuppressedMessage) {

  override def appendLog(level: Level.Value, message: => String): Unit = {
    val text = message
    if (appender.shouldLog(text)) {
      onActivity()
      appender.log(level, text, flowId)
    }
  }

  override def appendObjectEvent[T](level: Level.Value, event: => ObjectEvent[T]): Unit = {
    val objectEvent = event
    val text = renderObjectEvent(objectEvent)
    if (appender.shouldLog(text)) {
      onActivity()
      appender.log(level, text, flowId)
    }
  }

  private def renderObjectEvent(event: ObjectEvent[?]): String = {
    LogExchange.stringCodec(event.contentType) match {
      case Some(renderer) =>
        renderer.asInstanceOf[ShowLines[Any]].showLines(event.message).mkString("\n")
      case None => event.message.toString
    }
  }
}

object TCLoggerAppender {
  private def properties: ConsoleAppender.Properties =
    ConsoleAppender("tc-logger-properties", sbt.internal.util.ConsoleOut.NullConsoleOut).properties

  def muted(kind: String): Appender =
    ConsoleAppender(s"teamcity-muted-$kind-${System.nanoTime()}", sbt.internal.util.ConsoleOut.NullConsoleOut)
}
