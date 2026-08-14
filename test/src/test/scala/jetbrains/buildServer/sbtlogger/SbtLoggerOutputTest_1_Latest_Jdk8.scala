package jetbrains.buildServer.sbtlogger

/** Runs the current SBT 1 logger-output scenarios against the exact JDK 8 baseline. */
class SbtLoggerOutputTest_1_Latest_Jdk8
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt1_Latest_Jdk8)
    with SbtLoggerOutputTestsSbt1_9Plus
