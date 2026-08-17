package jetbrains.buildServer.sbtlogger

/** Runs the shared legacy logger-output scenarios against the latest SBT 1.0 release on the exact JDK 8 baseline. */
class SbtLoggerOutputTest_1_0_Jdk8
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt1_0_Jdk8)
    with SbtLoggerOutputTests_Sbt1_0_JDK8
