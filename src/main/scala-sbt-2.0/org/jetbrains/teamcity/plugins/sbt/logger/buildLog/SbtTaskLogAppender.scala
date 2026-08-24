// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import org.jetbrains.teamcity.plugins.sbt.logger.SbtTeamCityLoggerSettings
import sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal.SbtConsoleAppenderBridge
import sbt.internal.util.{Appender, ObjectEvent}
import sbt.util.{Level, LogExchange, ShowLines}

/**
 * Converts an SBT task's ordinary screen-log events into TeamCity Build Log messages.
 *
 * `SbtTeamCityLogger` installs this as the per-task `screen` appender when it runs under TeamCity and
 * `preserveConsole` is disabled. It must be a `ConsoleAppender` (through `SbtConsoleAppenderBridge`): SBT's
 * `MainAppender` applies a task's effective screen log level only to appenders of that type. The bridge gives this
 * appender a no-op `ConsoleOut`, while the overridden methods send the event to `SbtBuildEventReporter`. Thus each
 * eligible event is shown once in TeamCity rather than also being printed as an ordinary SBT console line.
 *
 * For a normal task, the appender preserves the SBT level, renders an `ObjectEvent` to text when necessary, and
 * associates the resulting message with the task's flow ID. For example, a task event
 * `appendLog(Level.Warn, "unused import")` becomes a TeamCity Build Log warning with text
 * `"[warn] unused import"` in that flow. A renderable object event such as compiler progress is likewise emitted as
 * a text message; an object event that renders to no lines deliberately emits nothing.
 *
 * Compiler-task events receive extra ordering and lifecycle handling. Before the first such event is reported, the
 * optional compilation-start callback is invoked exactly once, and concurrent events wait for it to complete. The
 * reporter can therefore emit `compilationStarted` before, for example, the first
 * `"[info] compiling 3 Scala sources"` message. Error events are also offered to the supplied initializer-error
 * callback; that callback decides whether the error represents a test-framework initialization failure.
 *
 * This class does not capture a process's raw stdout or stderr, parse compiler diagnostics, or create TeamCity test
 * or inspection events. Those responsibilities belong respectively to the process/SBT logging setup, the compiler
 * reporter, and the separate reporting path. It also does not report dependency-resolution progress or retain an
 * object event's channel, execution ID, or content type (except for the opt-in diagnostic details setting).
 *
 * Without this appender, ordinary task output would either remain only as plain SBT console text, with no TeamCity
 * `message` service message, severity, or task flow ID, or be lost if routed to the null console sink. In particular,
 * TeamCity could not reliably group concurrent task output and a compiler's first message could precede its
 * compilation lifecycle. In `preserveConsole` mode this appender is intentionally not installed: the plugin leaves
 * the normal SBT console presentation in place instead.
 *
 * @param buildEventReporter translates text and compiler lifecycle events into TeamCity Build Log service messages
 * @param flowId identifies the SBT task's TeamCity flow
 * @param isCompilerTask enables compiler-flow routing and lazy compilation-start handling
 * @param compilationStart callback that starts the compiler flow before its first reported event, when available
 * @param reportIfInitializerError receives error text and the task flow ID for optional test-initializer reporting
 */
final class SbtTaskLogAppender(
  buildEventReporter: SbtBuildEventReporter,
  flowId: String,
  isCompilerTask: Boolean,
  compilationStart: Option[() => Unit] = None,
  reportIfInitializerError: (String, String) => Unit = SbtTaskLogAppender.ignoreInitializerError
) extends SbtConsoleAppenderBridge(s"tc-logger-$flowId") {

  def this(
    buildEventReporter: SbtBuildEventReporter,
    flowId: String,
    isCompilerTask: Boolean
  ) =
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
    if (Level.Error.equals(level)) {
      reportIfInitializerError(text, flowId)
    }
    if (isCompilerTask) {
      requestCompilationStart()
      buildEventReporter.logCompilerMessage(level, text, flowId)
    } else {
      buildEventReporter.log(level, text, flowId)
    }
  }

  private def requestCompilationStart(): Unit = compilationStartLock.synchronized {
    compilationStart.foreach { start =>
      if (!compilationStartRequested) {
        start()
        compilationStartRequested = true
      }
    }
  }

  private def renderObjectEvent(event: ObjectEvent[?]): Option[String] = {
    val renderedObject = LogExchange.stringCodec(event.contentType) match {
      case Some(renderer) =>
        RenderedLines.toMultilineStringIfAny(renderer.asInstanceOf[ShowLines[Any]].showLines(event.message))
      case None =>
        Some(event.message.toString)
    }
    if (SbtTeamCityLoggerSettings.RenderObjectEventDetails.isEnabled)
      renderedObject.map(_ + SbtTaskLogAppender.objectEventDetails(event))
    else
      renderedObject
  }
}

object SbtTaskLogAppender {
  private val ignoreInitializerError: (String, String) => Unit = (_, _) => ()

  private def objectEventDetails(event: ObjectEvent[?]): String =
    s" (ObjectEvent details: channelName=${event.channelName}, execId=${event.execId}, contentType=${event.contentType})"

  def muted(kind: String): Appender = SbtConsoleAppenderBridge.nullConsoleOutAppender(kind)
}
