package jetbrains.buildServer.sbtlogger

/** Runs the shared logger-output scenarios against the latest supported SBT 1 release. */
class SbtLoggerOutputTest_1_Latest
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt1_Latest)
    with SbtLoggerOutputTestsSbt1_9Plus
