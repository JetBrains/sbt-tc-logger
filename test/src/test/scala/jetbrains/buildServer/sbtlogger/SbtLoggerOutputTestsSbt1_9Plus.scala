package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.{SbtExitCodeExpectation, SbtLoggerOutputTestCase}
import org.junit.Test

/** Scenarios supported by SBT 1.9.0 and later, beyond the supported SBT 1.4 baseline runtime. */
trait SbtLoggerOutputTestsSbt1_9Plus { this: SbtLoggerOutputTestBase =>

  // TW-53224 - SBT 1 addresses the IntegrationTest configuration with the historic `it:` command syntax.
  @Test
  def testReporting_IntegrationTest_TestQuickPassAndFailureReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/IntegrationTest_TestQuick",
      sbtCommands = Seq("it:testQuick"),
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))

  // TW-34982 and TW-36108 (GitHub #3)
  // The expected test-suite messages prove that JaCoCo instrumentation retains normal JUnit TeamCity reporting.
  // The expected coverage-summary messages are sbt-jacoco's ordinary log output, not a TeamCity coverage protocol:
  // they prove that the `jacoco` task generated its local report, rather than this being an equivalent JUnit-only run.
  @Test
  def testReporting_Jacoco_JUnitAndCoverageReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "jacoco",
      fixtureRootRelativePath = Some("test/testdata/1.9+"),
      sbtCommands = Seq("jacoco"),
      sbtOptions = Seq("--info"), // sbt-jacoco logs its report summary at info level.
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))
}
