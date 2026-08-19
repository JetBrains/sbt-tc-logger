package jetbrains.buildServer.sbtlogger

import org.junit.Test

/** Focused opt-in Coursier reporting contract for sbt 2.x. */
class SbtDetailedDependencyResolutionTest_2_0_Jdk17 extends SbtLoggerOutputTestBase(SbtTestsRuntime.Sbt2_0_Jdk17) {
  @Test
  def outcomesReported(): Unit = detailedDependencyResolution_CoursierOutcomesReported()

  @Test
  def failureIsWarningAndCloses(): Unit = detailedDependencyResolution_CoursierFailureIsWarningAndCloses()

  @Test
  def debugKeepsNativeLogging(): Unit = detailedDependencyResolution_DebugKeepsNativeLogging()

  @Test
  def preserveConsoleDisablesDetailedMode(): Unit = detailedDependencyResolution_PreserveConsoleDisablesDetailedMode()
}
