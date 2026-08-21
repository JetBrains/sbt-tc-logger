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

package sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal

import _root_.org.jetbrains.teamcity.plugins.sbt.logger.TCLogAppender
import sbt.util.{Level, Logger}
import sbt.{TestResultLogger, Tests}

import java.io.{PrintWriter, StringWriter}

final class RedirectTestResultLogger(
  delegate: TestResultLogger,
  appender: TCLogAppender,
  flowId: String,
  screenLevel: Level.Value
) extends TestResultLogger {
  // SBT 2 makes Tests.Output private[sbt], so this override must remain in an sbt.* package.
  // The code is otherwise source-compatible with SBT 1; split it back into target sources if the APIs diverge.
  override def run(log: Logger, results: Tests.Output, taskName: String): Unit = {
    val directTeamCityLogger = new RedirectTestResultLogger.DirectTeamCityLogger(appender, flowId, screenLevel)
    delegate.run(directTeamCityLogger, results, taskName)
  }
}

object RedirectTestResultLogger {

  private final class DirectTeamCityLogger(
    appender: TCLogAppender,
    flowId: String,
    screenLevel: Level.Value
  ) extends Logger {
    override def trace(error: => Throwable): Unit = {
      val buffer = new StringWriter
      error.printStackTrace(new PrintWriter(buffer))
      appender.log(Level.Error, buffer.toString, flowId)
    }

    override def success(message: => String): Unit =
      if (isEnabled(Level.Info)) appender.log(Level.Info, message, flowId)

    override def log(level: Level.Value, message: => String): Unit =
      if (isEnabled(level)) appender.log(level, message, flowId)

    private def isEnabled(level: Level.Value): Boolean = level.compare(screenLevel) >= 0
  }
}
