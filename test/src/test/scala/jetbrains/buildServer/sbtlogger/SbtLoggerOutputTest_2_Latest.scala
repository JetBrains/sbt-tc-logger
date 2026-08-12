package jetbrains.buildServer.sbtlogger

/** Runs the shared logger-output scenarios against the latest supported SBT 2 release. */
class SbtLoggerOutputTest_2_Latest
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt2_Latest)
    with SbtLoggerOutputTestsSbt2Plus
