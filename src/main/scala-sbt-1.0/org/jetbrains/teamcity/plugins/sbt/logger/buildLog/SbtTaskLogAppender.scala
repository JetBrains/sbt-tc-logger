// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import _root_.org.jetbrains.teamcity.plugins.sbt.logger.SbtTeamCityLoggerSettings
import _root_.sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal.SbtConsoleAppenderBridge
import _root_.sbt.internal.util.{Appender, ObjectEvent}
import _root_.sbt.util.{Level, LogExchange, ShowLines}

/**
 * Redirects ordinary SBT screen-appender events to TeamCity Build Log messages.
 *
 * This is the project's only TeamCity-specific SBT `ConsoleAppender`. Structured test and inspection reporting do
 * not pass through it.
 */
final class SbtTaskLogAppender(
  buildEventReporter: SbtBuildEventReporter,
  flowId: String,
  isCompilerTask: Boolean,
  compilationStart: Option[() => Unit] = None,
  reportIfInitializerError: (String, String) => Unit = SbtTaskLogAppender.ignoreInitializerError
) extends SbtConsoleAppenderBridge(s"tc-logger-$flowId") {
  def this(buildEventReporter: SbtBuildEventReporter, flowId: String, isCompilerTask: Boolean) =
    this(buildEventReporter, flowId, isCompilerTask, None)

  private val compilationStartLock = new Object
  private var compilationStartRequested = false

  override def appendLog(level: Level.Value, message: => String): Unit =
    report(level, message)

  override def appendObjectEvent[T](level: Level.Value, event: => ObjectEvent[T]): Unit = {
    val objectEvent = event
    renderObjectEvent(objectEvent).foreach(report(level, _))
  }

  private def report(level: Level.Value, message: => String): Unit = {
    val text = message
    if (Level.Error.equals(level)) reportIfInitializerError(text, flowId)
    if (isCompilerTask) {
      requestCompilationStart()
      buildEventReporter.logCompilerMessage(level, text, flowId)
    } else buildEventReporter.log(level, text, flowId)
  }

  private def requestCompilationStart(): Unit = compilationStartLock.synchronized {
    compilationStart.foreach { start =>
      if (!compilationStartRequested) {
        start()
        compilationStartRequested = true
      }
    }
  }

  private def renderObjectEvent(event: ObjectEvent[_]): Option[String] = {
    val renderedObject = LogExchange.stringCodec(event.contentType) match {
      case Some(renderer) =>
        SbtObjectEventRenderer.combine(renderer.asInstanceOf[ShowLines[Any]].showLines(event.message))
      case None => Some(event.message.toString)
    }
    if (SbtTeamCityLoggerSettings.RenderObjectEventDetails.isEnabled)
      renderedObject.map(_ + SbtTaskLogAppender.objectEventDetails(event))
    else renderedObject
  }
}

object SbtTaskLogAppender {
  private val ignoreInitializerError: (String, String) => Unit = (_, _) => ()

  private def objectEventDetails(event: ObjectEvent[_]): String =
    s" (ObjectEvent details: channelName=${event.channelName}, execId=${event.execId}, contentType=${event.contentType})"

  def muted(kind: String): Appender = SbtConsoleAppenderBridge.nullConsoleOutAppender(kind)
}
