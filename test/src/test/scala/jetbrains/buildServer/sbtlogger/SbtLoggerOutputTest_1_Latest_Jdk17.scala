package jetbrains.buildServer.sbtlogger

/** Runs the current SBT 1 logger-output scenarios against the exact JDK 17 baseline. */
class SbtLoggerOutputTest_1_Latest_Jdk17
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt1_Latest_Jdk17)
    with SbtLoggerOutputTestsSbt1_9Plus
    with SbtLoggerOutputTests_SinceJDK11
