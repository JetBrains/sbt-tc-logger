package org.jetbrains.teamcity.plugins.sbt.logger

import org.junit.Test

/** Focused opt-in Coursier reporting contract for the latest sbt 1.x line. */
class SbtDetailedDependencyResolution_TestSbt1_12_Jdk17 extends SbtLoggerOutputTestBase(SbtTestsRuntime.Sbt1_12_Jdk17) {
  @Test
  def outcomesReported(): Unit = detailedDependencyResolution_CoursierOutcomesReported()

  @Test
  def failureIsWarningAndCloses(): Unit = detailedDependencyResolution_CoursierFailureIsWarningAndCloses()

  @Test
  def debugKeepsNativeLogging(): Unit = detailedDependencyResolution_DebugKeepsNativeLogging()

  @Test
  def preserveConsoleDisablesDetailedMode(): Unit = detailedDependencyResolution_PreserveConsoleDisablesDetailedMode()
}
