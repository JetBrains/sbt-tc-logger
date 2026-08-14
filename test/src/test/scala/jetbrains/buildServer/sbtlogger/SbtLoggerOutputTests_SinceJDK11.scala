package jetbrains.buildServer.sbtlogger

import org.junit.Test

/** TW-35693 scenario for fixtures supported on JDK 11 and later. */
trait SbtLoggerOutputTests_SinceJDK11 { this: SbtLoggerOutputTestBase =>

  @Test
  def testReporting_ScalaTest_ErrorLikeOutputNotCompilationFailure(): Unit =
    runScalaTestErrorLikeOutputNotCompilationFailureCase()
}
