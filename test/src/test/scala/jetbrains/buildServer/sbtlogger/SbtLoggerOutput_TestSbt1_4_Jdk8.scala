package jetbrains.buildServer.sbtlogger

/** Runs the supported SBT 1.4 native-appender baseline on the exact JDK 8 runtime. */
class SbtLoggerOutput_TestSbt1_4_Jdk8
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt1_4_Jdk8)
