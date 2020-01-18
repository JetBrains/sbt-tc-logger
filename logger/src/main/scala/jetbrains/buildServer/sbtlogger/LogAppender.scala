/*
 * Copyright 2013-2020 JetBrains s.r.o.
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

  def log(level: sbt.Level.Value, message: => String, flowId: String)

  def log(level: String, message: => String, flowId: String)

  def compilationBlockStart(flowId: String)

  def compilationBlockEnd(flowId: String)

  def compilationTestBlockStart(flowId: String)

  def compilationTestBlockEnd(flowId: String)

  def testSuiteStart(name: String, flowId: String)

  def testSuiteSuccessfulResult(name: String, flowId: String)

  def testSuiteFailResult(name: String, t: Throwable, flowId: String)

  def testStart(name: String, flowId: String)

  def testFinished(name: String, status: String, duration: Long, flowId: String)

  def testFailed(name: String, details: String, flowId: String)

  def testSkipped(name: String, flowId: String)

  def testCancelled(name: String, flowId: String)

}
