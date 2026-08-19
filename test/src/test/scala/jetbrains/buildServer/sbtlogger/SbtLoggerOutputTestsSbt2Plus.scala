package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.{SbtLoggerOutputTestCase, SbtProcessResultExpectation}
import org.junit.Test

/** Scenarios supported by SBT 2.0.0 and later. */
trait SbtLoggerOutputTestsSbt2Plus { this: SbtLoggerOutputTestBase =>

  // TW-53224 - SBT 2's slash syntax invokes the same IntegrationTest quick-test task as SBT 1's `it:testQuick`.
  @Test
  def testReporting_IntegrationTest_TestQuickPassAndFailureReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = "integration-test-quick",
      fixture = "testSupport/IntegrationTest_TestQuick",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("IntegrationTest / testQuick"),
      expectedResult = SbtProcessResultExpectation.Success
    ))

  // TW-34982 and TW-36108 (GitHub #3)
  // The expected test-suite messages prove that JaCoCo instrumentation retains normal JUnit TeamCity reporting.
  // The expected coverage-summary messages are sbt-jacoco's ordinary log output, not a TeamCity coverage protocol:
  // they prove that the `jacoco` task generated its local report, rather than this being an equivalent JUnit-only run.
  @Test
  def testReporting_Jacoco_JUnitAndCoverageReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = "jacoco",
      fixture = "jacoco",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("jacoco"),
      expectedResult = SbtProcessResultExpectation.Success,
      sbtOptions = Seq("--info"), // sbt-jacoco logs its report summary at info level.
    ))
}
