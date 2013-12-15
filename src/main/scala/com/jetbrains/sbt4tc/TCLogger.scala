package com.jetbrains.sbt4tc

import sbt._


class TCLogger(ap: LogAppender) extends BasicLogger {

  val appender: LogAppender = ap

  def logAll(events: Seq[LogEvent]) =  { events.foreach(log) }

  def log(level: sbt.Level.Value, message: => String) {
      appender.log(level,message,Thread.currentThread().getId())
  }

  def control(event: ControlEvent.Value, message: => String) {
      log(sbt.Level.Info, message)
  }

  def success(message: => String) {
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