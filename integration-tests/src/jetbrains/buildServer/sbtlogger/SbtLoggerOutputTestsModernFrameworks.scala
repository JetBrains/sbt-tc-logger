package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.{SbtLoggerOutputTestCase, SbtProcessResultExpectation}
import org.junit.Test

/** Scala 3.8.4 reporting scenarios for the current versions of each supported test-framework family. */
trait SbtLoggerOutputTestsModernFrameworks { this: SbtLoggerOutputTestBase =>

  @Test def testReporting_ModernScalaTestReported(): Unit = runModernFramework(
    "modern-scalatest", "testSupport/Modern_ScalaTest")

  @Test def testReporting_ModernJUnit4Reported(): Unit = runModernFramework(
    "modern-junit4", "testSupport/Modern_JUnit4")

  @Test def testReporting_ModernJupiterReported(): Unit = runModernFramework(
    "modern-jupiter", "testSupport/Modern_Jupiter")

  @Test def testReporting_ModernMUnitReported(): Unit = runModernFramework(
    "modern-munit", "testSupport/Modern_MUnit")

  private def runModernFramework(scenarioId: String, fixture: String): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = scenarioId,
      fixture = fixture,
      setupCommands = Seq.empty,
      behaviorCommands = Seq("test"),
      expectedResult = SbtProcessResultExpectation.Failure,
      sbtOptions = Seq("-Dteamcity.sbt.logger.showTestTaskOutput=false")
    ))
}
