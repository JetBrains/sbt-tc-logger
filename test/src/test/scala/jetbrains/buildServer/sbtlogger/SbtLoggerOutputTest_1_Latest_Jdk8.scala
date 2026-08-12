package jetbrains.buildServer.sbtlogger

import org.junit.{Ignore, Test}

/** Runs the current SBT 1 logger-output scenarios against the exact JDK 8 baseline. */
class SbtLoggerOutputTest_1_Latest_Jdk8
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt1_Latest_Jdk8)
    with SbtLoggerOutputTestsSbt1_9Plus {

  /** Logback 1.6.2 requires Java 11; the same fixture runs in [[SbtLoggerOutputTest_1_Latest_Jdk17]]. */
  @Ignore("Logback 1.6.2 requires Java 11; covered by SbtLoggerOutputTest_1_Latest_Jdk17.")
  @Test
  override def testReporting_ScalaTest_ErrorLikeOutputNotCompilationFailure(): Unit = ()
}
