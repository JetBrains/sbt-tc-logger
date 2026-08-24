// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

private[buildLog] object RenderedLines {
  /**
   * Converts renderer output to optional text while preserving the distinction between no rendered lines and one
   * intentional blank line. SBT test lifecycle protocol events use the former and framework output may legitimately
   * use the latter.
   *
   * Examples:
   *   - `Seq()` becomes `None`, so the event is not reported.
   *   - `Seq("")` becomes `Some("")`, preserving one intentional blank line.
   *   - `Seq("test output")` becomes `Some("test output")`.
   *   - `Seq("first line", "second line")` becomes `Some("first line\\nsecond line")`.
   */
  def toMultilineStringIfAny(lines: Seq[String]): Option[String] =
    if (lines.isEmpty)
      None
    else
      Some(lines.mkString("\n"))
}
