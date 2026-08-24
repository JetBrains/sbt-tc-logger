package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.{SbtLoggerOutputTestCase, SbtProcessResultExpectation}
import org.junit.Test

/** Ensures a suite-construction initializer error remains visible in the Build Log and fails the SBT process. */
class SbtInitializerErrorCoverageTest {

  @Test def suiteConstructionFailure_LeavesSuiteUnfinishedAndFailsSbt1(): Unit =
    runInitializerErrorCase(SbtTestsRuntime.Sbt1_12_Jdk17)

  @Test def suiteConstructionFailure_LeavesSuiteUnfinishedAndFailsSbt2(): Unit =
    runInitializerErrorCase(SbtTestsRuntime.Sbt2_0_Jdk17)

  private def runInitializerErrorCase(runtime: SbtTestsRuntime): Unit =
    new SbtLoggerOutputTestBase(runtime) {}.runCase(SbtLoggerOutputTestCase(
      scenarioId = "initializer-error-suite-construction",
      fixture = "testSupport/ScalaTest_InitializerError",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("clean", "test"),
      expectedResult = SbtProcessResultExpectation.Failure
    ))
}
