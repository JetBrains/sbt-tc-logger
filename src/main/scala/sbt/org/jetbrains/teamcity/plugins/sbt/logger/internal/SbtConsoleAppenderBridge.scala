// Copyright © 2013–2026 JetBrains s.r.o.
package sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal

import sbt.internal.util.{Appender, ConsoleAppender, ConsoleOut}

/**
 * Supplies the `private[sbt]` `ConsoleAppender` construction details to the normal SBT integration package.
 *
 * Every appender created here uses `ConsoleOut.NullConsoleOut`, SBT's no-op console sink: its `print`,
 * `println`, and `flush` methods discard their input. Consequently, an event routed to such an appender does
 * not produce a line on SBT's original console output.
 *
 * `SbtTaskLogAppender` overrides the logging methods to report events to TeamCity instead, so its TeamCity
 * Build Log messages are not discarded. The `nullConsoleOutAppender` factory is used where events must be
 * muted completely, preventing duplicate SBT-console output.
 */
abstract class SbtConsoleAppenderBridge(name: String)
  extends ConsoleAppender(
    name = name,
    properties = SbtConsoleAppenderBridge.nullConsoleOutProperties,
    suppressedMessage = ConsoleAppender.noSuppressedMessage
  )

object SbtConsoleAppenderBridge {
  private def nullConsoleOutProperties: ConsoleAppender.Properties =
    ConsoleAppender("teamcity-sbt-logger-properties", ConsoleOut.NullConsoleOut).properties

  def nullConsoleOutAppender(kind: String): Appender =
    ConsoleAppender(s"teamcity-muted-$kind-${System.nanoTime()}", ConsoleOut.NullConsoleOut)
}
