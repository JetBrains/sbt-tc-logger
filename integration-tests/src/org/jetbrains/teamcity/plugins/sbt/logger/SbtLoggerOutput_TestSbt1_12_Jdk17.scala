package org.jetbrains.teamcity.plugins.sbt.logger

/** Runs the latest SBT 1.12 logger-output scenarios against the exact JDK 17 baseline. */
class SbtLoggerOutput_TestSbt1_12_Jdk17
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt1_12_Jdk17)
    with SbtLoggerOutputTestsSbt1_9Plus
    with SbtLoggerOutputTests_SinceJDK11
    with SbtLoggerOutputTestsModernFrameworks
    with SbtLoggerOutputTestsTestControlMatrix
