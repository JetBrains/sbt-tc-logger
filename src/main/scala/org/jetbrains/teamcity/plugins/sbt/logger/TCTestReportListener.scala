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

import java.io.{PrintWriter, StringWriter}

import sbt.*
import sbt.testing.{NestedTestSelector, OptionalThrowable, Status, TestSelector}


/**
 * Adapts SBT's test-reporting callbacks to TeamCity's structured test service messages.
 *
 * `SbtTeamCityLogger` installs this listener in `testListeners` when it detects that SBT is
 * running under TeamCity.  The listener receives suite and test lifecycle callbacks from SBT
 * test frameworks and adapters, and writes the corresponding messages through [[LogAppender]].
 * TeamCity then uses those messages to build the Tests tab hierarchy and to associate failures,
 * durations, and ignored tests with their individual test nodes.
 *
 * The normal successful lifecycle looks as follows:
 *
 * {{{
 * SBT callbacks:
 *   startGroup("example.CalculatorSpec")
 *   testEvent(Event("adds two numbers", Status.Success, 12 ms))
 *   endGroup("example.CalculatorSpec", TestResult)
 *
 * TeamCity service messages:
 *   ##teamcity[testSuiteStarted name='example.CalculatorSpec' flowId='…']
 *   ##teamcity[testStarted name='example.CalculatorSpec.adds two numbers'
 *                         captureStandardOutput='true' flowId='…']
 *   ##teamcity[testFinished name='example.CalculatorSpec.adds two numbers'
 *                          duration='12' flowId='…']
 *   ##teamcity[testSuiteFinished name='example.CalculatorSpec' flowId='…']
 *
 * Rendered TeamCity test tree:
 *   example.CalculatorSpec
 *     adds two numbers  (passed, 12 ms)
 * }}}
 *
 * A failed test has the same enclosing lifecycle, with a `testFailed` message between
 * `testStarted` and `testFinished`:
 *
 * {{{
 * SBT callback:
 *   testEvent(Event("divides by zero", Status.Failure, throwable = assertionError))
 *
 * TeamCity service messages:
 *   ##teamcity[testStarted name='example.CalculatorSpec.divides by zero' …]
 *   ##teamcity[testFailed name='example.CalculatorSpec.divides by zero'
 *                         details='java.lang.AssertionError: …' …]
 *   ##teamcity[testFinished name='example.CalculatorSpec.divides by zero' …]
 * }}}
 *
 * ==Event mapping==
 *
 * For every SBT `sbt.testing.Event` in a `TestEvent`, this listener emits `testStarted` and
 * `testFinished`.  Between them, it maps `Success` to no additional event, `Error` and `Failure`
 * to `testFailed`, and `Skipped`, `Ignored`, `Pending`, and `Canceled` to `testIgnored`.  A
 * failure's supplied throwable is rendered as the `testFailed` details.  SBT reports a group
 * setup/teardown error through `endGroup(name, Throwable)`; that becomes a `testSuiteFinished`
 * message carrying the suite failure message and details.
 *
 * Test names come from the test framework's selector.  [[sbt.testing.TestSelector]] names are
 * qualified with the test class when necessary; [[sbt.testing.NestedTestSelector]] names also
 * include the nested suite identifier.  Therefore, a framework or SBT adapter that does not emit
 * `TestEvent`s cannot be made to appear as individual tests by this listener.
 *
 * ==Relationship to other test-related output==
 *
 * This listener is deliberately separate from the two non-structured output paths:
 *
 *  - `TestResultLogger` is invoked once after a test task finishes.  The
 *    `teamcity.sbt.logger.useTeamCityTestResultLogger` control selects the TeamCity silent,
 *    failure-preserving result logger or the configured SBT result logger.  That choice affects
 *    aggregate result output and failure semantics, not the per-test service messages emitted
 *    here.
 *  - `TCLoggerAppender` forwards ordinary SBT task log events as TeamCity `message` service
 *    messages.  `teamcity.sbt.logger.showTestTaskOutput` controls that ordinary test-task stream.
 *    It does not control this listener's `testSuiteStarted`, `testStarted`, `testFailed`,
 *    `testIgnored`, `testFinished`, and `testSuiteFinished` messages.
 *
 * Consequently, this listener remains active when either control is `true` or `false`.  It also
 * remains active with `preserveConsole=true`: that flag retains SBT's regular console and result
 * logger configuration, but does not disable TeamCity's structured per-test reporting.
 *
 * SBT delivers the lifecycle callbacks on a worker thread.  The listener derives the TeamCity
 * flow ID from that thread so messages from the same worker are correlated, including when SBT
 * executes tests concurrently.  It does not choose test parallelism, control process exit, or
 * parse framework console output.
 *
 * @param ap destination for TeamCity service messages
 */
class TCTestReportListener(ap: LogAppender) extends TestReportListener {

  val appender: LogAppender = ap

  def startGroup(name: String): Unit = {
    appender.testSuiteStart(name, flowId)
  }

  /** called for each test method or equivalent */
  def testEvent(event: TestEvent): Unit = {
    event.detail.foreach(logSingleTest)
  }

  def formattedException(t: OptionalThrowable): String = {
    if (t.isDefined) {
      val w = new StringWriter
      val p = new PrintWriter(w)
      t.get.printStackTrace(p)
      w.toString
    } else ""
  }

  def flowId: String = {
    Thread.currentThread().getId.toString
  }

  protected def logSingleTest(event: sbt.testing.Event): Unit = {
    val fqn = event.fullyQualifiedName
    val status = event.status.toString
    val duration = event.duration
    val throwable = event.throwable

    val testName = event.selector match {
      case s: TestSelector =>
        if (fqn == s.testName()) fqn
        else fqn + "." + s.testName

      case ns: NestedTestSelector =>
        val prefix =
          if (fqn == ns.testName()) ""
          else fqn + "."
        prefix + ns.suiteId + "." + ns.testName

      case _ => fqn
    }

    appender.testStart(s"$testName", flowId)

    event.status match {
      case Status.Success => // nothing extra to report
      case Status.Error | Status.Failure =>
        appender.testFailed(testName, formattedException(throwable), flowId)
      case Status.Skipped | Status.Ignored | Status.Pending =>
        appender.testSkipped(testName, flowId)
      case Status.Canceled =>
        appender.testSkipped(testName, flowId)
    }

    appender.testFinished(s"$testName", status, duration, flowId)
  }

  /** called if there was an error during test */
  def endGroup(name: String, t: Throwable): Unit = {
    appender.testSuiteFailResult(name, t, flowId)
  }

  /** called if test completed */
  def endGroup(name: String, result: TestResult): Unit = {
    appender.testSuiteSuccessfulResult(name, flowId)
  }


}
