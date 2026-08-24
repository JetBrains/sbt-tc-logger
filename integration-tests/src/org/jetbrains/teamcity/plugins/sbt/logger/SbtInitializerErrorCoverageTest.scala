package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.{SbtLoggerOutputTestCase, SbtProcessResultExpectation}
import org.junit.Test

/** Focused #12 regression coverage for an exception raised while a ScalaTest suite is constructed. */
class SbtInitializerErrorCoverageTest {

  @Test def suiteConstructionFailure_IsReportedAndFailsSbt1(): Unit =
    runInitializerErrorCase(SbtTestsRuntime.Sbt1_12_Jdk17)

  @Test def suiteConstructionFailure_IsReportedAndFailsSbt2(): Unit =
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
