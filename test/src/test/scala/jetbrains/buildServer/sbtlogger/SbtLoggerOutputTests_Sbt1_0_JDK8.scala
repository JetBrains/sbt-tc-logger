package jetbrains.buildServer.sbtlogger

import org.junit.Test

/** TW-35693 scenario for the SBT 1.0/JDK 8 fixture, which uses a JDK 8-compatible Logback version. */
trait SbtLoggerOutputTests_Sbt1_0_JDK8 { this: SbtLoggerOutputTestBase =>

  @Test
  def testReporting_ScalaTest_ErrorLikeOutputNotCompilationFailure(): Unit =
    runScalaTestErrorLikeOutputNotCompilationFailureCase()
}
