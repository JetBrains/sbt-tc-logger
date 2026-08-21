package org.jetbrains.teamcity.plugins.sbt.logger

/** Runs the shared logger-output scenarios against the latest SBT 2.0 release on exact JDK 17. */
class SbtLoggerOutput_TestSbt2_0_Jdk17
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt2_0_Jdk17)
    with SbtLoggerOutputTestsSbt2Plus
    with SbtLoggerOutputTests_SinceJDK11
    with SbtLoggerOutputTestsModernFrameworks
    with SbtLoggerOutputTestsTestControlMatrix
