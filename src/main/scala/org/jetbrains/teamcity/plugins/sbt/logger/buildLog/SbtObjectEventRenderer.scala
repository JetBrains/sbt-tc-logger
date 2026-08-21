// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

private[buildLog] object SbtObjectEventRenderer {
  /**
   * Preserves the console appender's distinction between an event with no rendered lines and one intentional blank
   * line. SBT test lifecycle protocol events use the former and framework output may legitimately use the latter.
   */
  def combine(lines: Seq[String]): Option[String] =
    if (lines.isEmpty) 
      None 
    else 
      Some(lines.mkString("\n"))
}
