package jetbrains.buildServer.sbtlogger

/** Runs the shared logger-output scenarios against the latest SBT 2.0 release on exact JDK 17. */
class SbtLoggerOutputTest_2_0_Jdk17
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt2_0_Jdk17)
    with SbtLoggerOutputTestsSbt2Plus
    with SbtLoggerOutputTests_SinceJDK11
