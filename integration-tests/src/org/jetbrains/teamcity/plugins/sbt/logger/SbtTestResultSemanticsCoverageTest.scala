package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.{SbtLoggerOutputTestCase, SbtProcessResultExpectation}
import org.junit.Test

/** Direct #9 regression coverage for the semantic result of a failed Test / test task. */
class SbtTestResultSemanticsCoverageTest {

  @Test def failedTestResult_IsIncompleteInSbt1(): Unit =
    runResultSemanticsCase(SbtTestsRuntime.Sbt1_12_Jdk17)

  @Test def failedTestResult_IsIncompleteInSbt2(): Unit =
    runResultSemanticsCase(SbtTestsRuntime.Sbt2_0_Jdk17)

  private def runResultSemanticsCase(runtime: SbtTestsRuntime): Unit =
    new SbtLoggerOutputTestBase(runtime) {}.runCase(SbtLoggerOutputTestCase(
      scenarioId = "test-result-is-incomplete",
      fixture = "testSupport/JUnit_TestResultIncomplete",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("verifyTestResultIsIncomplete"),
      expectedResult = SbtProcessResultExpectation.Success,
      verification = SbtTestExecutionFailureSemanticContracts.IncompleteResult
    ))
}
