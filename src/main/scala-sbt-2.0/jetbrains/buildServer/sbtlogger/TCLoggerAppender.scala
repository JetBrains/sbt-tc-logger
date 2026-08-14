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

import sbt.internal.util.{Appender, ConsoleAppender, ObjectEvent, SuppressedTraceContext}
import sbt.util.Level
import jetbrains.buildServer.sbtlogger.LogAppender

import scala.Option

/** SBT 2 appender boundary used by the shared TeamCity logger. */
class TCLoggerAppender(appender: LogAppender, scope: String) extends Appender {
  private val delegate = ConsoleAppender(s"tc-logger-$scope")

  override def name: String = delegate.name
  override def properties: ConsoleAppender.Properties = delegate.properties
  override def suppressedMessage: SuppressedTraceContext => Option[String] = delegate.suppressedMessage
  override def close(): Unit = ()

  override def appendLog(level: Level.Value, message: => String): Unit = {
    appender.log(level, message, scope)
  }

  override def appendObjectEvent[T](level: Level.Value, event: => ObjectEvent[T]): Unit = {
    appender.log(level, event.message.toString, scope)
  }
}
