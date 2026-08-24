package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.{SbtLoggerOutputTestCase, SbtProcessResultExpectation}
import org.junit.Test

/** Focused #12 regression coverage for a test JVM that terminates while SBT is forking tests. */
class SbtForkedTestFailureCoverageTest {

  @Test def childJvmExit_FailsParentSbt1(): Unit =
    runForkedFailureCase(SbtTestsRuntime.Sbt1_12_Jdk17)

  @Test def childJvmExit_FailsParentSbt2(): Unit =
    runForkedFailureCase(SbtTestsRuntime.Sbt2_0_Jdk17)

  private def runForkedFailureCase(runtime: SbtTestsRuntime): Unit =
    new SbtLoggerOutputTestBase(runtime) {}.runCase(SbtLoggerOutputTestCase(
      scenarioId = "forked-test-child-exit",
      fixture = "testSupport/JUnit_ForkedChildFailure",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("test"),
      expectedResult = SbtProcessResultExpectation.Failure
    ))
}
