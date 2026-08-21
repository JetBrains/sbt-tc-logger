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

package org.jetbrains.teamcity.plugins.sbt.logger

trait LogAppender {

  def log(level: sbt.Level.Value, message: => String, flowId: String): Unit

  def log(level: String, message: => String, flowId: String): Unit

  /** Routes a compiler-task event according to the active compiler lifecycle. */
  def logCompilerTask(level: sbt.Level.Value, message: => String, compilerFlowId: String): Unit

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
