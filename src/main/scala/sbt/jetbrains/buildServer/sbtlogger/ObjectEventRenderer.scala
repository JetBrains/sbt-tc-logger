/*
 * Copyright 2013-2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbt.jetbrains.buildServer.sbtlogger

private[sbtlogger] object ObjectEventRenderer {
  /**
   * Preserves the console appender's distinction between an event with no rendered lines and one intentional blank
   * line. SBT test lifecycle protocol events use the former and framework output may legitimately use the latter.
   */
  def combine(lines: Seq[String]): Option[String] =
    if (lines.isEmpty) None else Some(lines.mkString("\n"))
}
