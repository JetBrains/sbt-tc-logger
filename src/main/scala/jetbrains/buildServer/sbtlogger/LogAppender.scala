/*
 * Copyright 2013-2021 JetBrains s.r.o.
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

trait LogAppender {

  /**
   * Whether a logger event should become a TeamCity message.  Appender adapters
   * consult this before opening a phase lifecycle, so a deliberately suppressed
   * SBT summary cannot reopen a compiler block after it has finished.
   */
  def shouldLog(message: String): Boolean = true

  def log(level: sbt.Level.Value, message: => String, flowId: String): Unit

  def log(level: String, message: => String, flowId: String): Unit

  def dependencyBlockStart(flowId: String, projectName: Option[String], inTest: Boolean): Unit

  def dependencyBlockEnd(flowId: String): Unit

  /** Instruments SBT's unconfigured `update` task without duplicating its configuration-scoped delegate. */
  def directDependencyBlockStart(flowId: String, projectName: Option[String]): Unit

  def directDependencyBlockEnd(flowId: String): Unit

  def compilationBlockStart(flowId: String, projectName: Option[String]): Unit

  def compilationBlockEnd(flowId: String, projectName: Option[String]): Unit

  def compilationTestBlockStart(flowId: String, projectName: Option[String]): Unit

  def compilationTestBlockEnd(flowId: String, projectName: Option[String]): Unit

  def testSuiteStart(name: String, flowId: String): Unit

  def testSuiteSuccessfulResult(name: String, flowId: String): Unit

  def testSuiteFailResult(name: String, t: Throwable, flowId: String): Unit

  def testStart(name: String, flowId: String): Unit

  def testFinished(name: String, status: String, duration: Long, flowId: String): Unit

  def testFailed(name: String, details: String, flowId: String): Unit

  def testSkipped(name: String, flowId: String): Unit

  def testCancelled(name: String, flowId: String): Unit

}
