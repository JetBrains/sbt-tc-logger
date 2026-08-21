package org.jetbrains.teamcity.plugins.sbt.logger

import org.junit.Test

/** Focused opt-in Coursier reporting contract for sbt 2.x. */
class SbtDetailedDependencyResolution_TestSbt2_0_Jdk17 extends SbtLoggerOutputTestBase(SbtTestsRuntime.Sbt2_0_Jdk17) {
  @Test
  def outcomesReported(): Unit = detailedDependencyResolution_CoursierOutcomesReported()

  @Test
  def failureIsWarningAndCloses(): Unit = detailedDependencyResolution_CoursierFailureIsWarningAndCloses()

  @Test
  def debugKeepsNativeLogging(): Unit = detailedDependencyResolution_DebugKeepsNativeLogging()

  @Test
  def preserveConsoleDisablesDetailedMode(): Unit = detailedDependencyResolution_PreserveConsoleDisablesDetailedMode()
}
