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

import org.apache.logging.log4j.core
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.message.{ObjectMessage, ReusableObjectMessage}
import sbt.internal.util.StringEvent


class TCLoggerAppender(appender: LogAppender, scope: String) extends
  AbstractAppender("tc-logger-" + scope, null, PatternLayout.createDefaultLayout(), true) {

  def appendMessageContent(level: Any, parameter: AnyRef, flowId: String): Unit = {
    parameter match {
      case o: StringEvent => appender.log(level.toString, o.message, flowId)
      case _ => appender.log(level.toString, parameter.toString, flowId)
    }
  }

  def appendLog(level: Any, message: Any, flowId: String): Unit = {
    appender.log(level.toString, message.toString, flowId)
  }

  override def append(event: core.LogEvent): Unit = {
    event.getMessage match {
      case o: ObjectMessage => appendMessageContent(event.getLevel, o.getParameter, event.getThreadId.toString)
      case o: ReusableObjectMessage => appendMessageContent(event.getLevel, o.getParameter, event.getThreadId.toString)
      case _ => appendLog(event.getLevel, event.getMessage.getFormattedMessage, event.getThreadId.toString)
    }
  }
}
