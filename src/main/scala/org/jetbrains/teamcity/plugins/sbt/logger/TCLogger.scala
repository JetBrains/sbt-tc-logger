// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import sbt.*


class TCLogger(appender: LogAppender, scope: String) extends BasicLogger {

  def logAll(events: Seq[LogEvent]): Unit = {
    events.foreach(log)
  }

  def log(level: sbt.Level.Value, message: => String): Unit = {
      if (level==Level.Debug || level==Level.Info) {
        //we don't need to wrap debug and info messages, we will show them as is
        return
      }
      appender.log(level, message, scope)
  }

  def control(event: ControlEvent.Value, message: => String): Unit = {
      log(sbt.Level.Info, message)
  }

  def success(message: => String): Unit = {
    if(successEnabled) {
      log(sbt.Level.Info, message)
    }
  }

  def trace(t: => Throwable): Unit = {
    val traceLevel = getTrace
    if(traceLevel >= 0)
      println(StackTrace.trimmed(t, traceLevel))
  }

}
