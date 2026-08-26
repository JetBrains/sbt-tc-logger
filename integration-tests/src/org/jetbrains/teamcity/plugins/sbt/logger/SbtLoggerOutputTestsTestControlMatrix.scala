package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.{
  SbtLoggerOutputTestCase,
  SbtOutputVerificationSelection,
  SbtProcessResultExpectation
}
import org.junit.Test

/** The strongest combined non-default result-logger/output case for secondary SBT test entry points. */
trait SbtLoggerOutputTestsTestControlMatrix { this: SbtLoggerOutputTestBase =>

  @Test def testReporting_JUnitTestOnly_ConfiguredResultWithoutTaskOutput(): Unit =
    runConfiguredResultWithoutTaskOutputCase(
      "junit-test-only-configured-hidden",
      "testOnly thisis.a.test.ATest",
      SbtJUnitResultLoggerSemanticContracts.TestOnlyConfiguredHidden
    )

  @Test def testReporting_JUnitTestQuick_ConfiguredResultWithoutTaskOutput(): Unit =
    runConfiguredResultWithoutTaskOutputCase(
      "junit-test-quick-configured-hidden",
      "testQuick",
      SbtJUnitResultLoggerSemanticContracts.TestQuickConfiguredHidden
    )

  @Test def testReporting_TeamCityResultLoggerOverridesCustomNoThrowLogger(): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = "custom-result-logger-teamcity-hidden",
      fixture = "testSupport/JUnit_CustomResultLogger",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("test"),
      expectedResult = SbtProcessResultExpectation.Failure,
      sbtOptions = Seq("-Dteamcity.sbt.logger.showTestTaskOutput=false"),
      verification = SbtJUnitResultLoggerSemanticContracts.CustomTeamCityResultHidden
    ))

  @Test def testReporting_CustomConfigurationUsesConfiguredResultWithoutTaskOutput(): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = "integration-test-quick-configured-hidden",
      fixture = "testSupport/IntegrationTest_TestQuick",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("IntegrationTest / testQuick"),
      expectedResult = SbtProcessResultExpectation.Failure,
      sbtOptions = Seq(
        "-Dteamcity.sbt.logger.useTeamCityTestResultLogger=false",
        "-Dteamcity.sbt.logger.showTestTaskOutput=false"
      ),
      verification = SbtJUnitResultLoggerSemanticContracts.IntegrationTestQuickConfiguredHidden
    ))

  private def runConfiguredResultWithoutTaskOutputCase(
    scenarioId: String,
    command: String,
    verification: SbtOutputVerificationSelection
  ): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = scenarioId,
      fixture = "testSupport/JUnit_PassAndFailure",
      setupCommands = Seq.empty,
      behaviorCommands = Seq(command),
      expectedResult = SbtProcessResultExpectation.Failure,
      sbtOptions = Seq(
        "-Dteamcity.sbt.logger.useTeamCityTestResultLogger=false",
        "-Dteamcity.sbt.logger.showTestTaskOutput=false"
      ),
      verification = verification
    ))
}
