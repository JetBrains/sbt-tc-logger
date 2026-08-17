package jetbrains.buildServer.sbtlogger

/** Runs the latest SBT 1.12 logger-output scenarios against the exact JDK 8 baseline. */
class SbtLoggerOutputTest_1_12_Jdk8
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt1_12_Jdk8)
    with SbtLoggerOutputTestsSbt1_9Plus
