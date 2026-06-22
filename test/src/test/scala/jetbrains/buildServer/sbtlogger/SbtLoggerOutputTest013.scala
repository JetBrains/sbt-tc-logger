package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.{IntegrationTestLayout, SbtOutputVerifier}

import java.io.File
import org.junit.{Ignore, Test}

/**
 * Runs logger-output integration scenarios against the sbt 0.13 runtime.
 *
 * Most scenarios are inherited from [[SbtLoggerOutputTestsCommon]]. This suite only records sbt 0.13-specific fixture
 * support and helper methods.
 */
class SbtLoggerOutputTest013 extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt013) {
  @Ignore("fixture exists only for the sbt 1.x suite")
  @Test
  override def testJUnit(): Unit = ()

  /**
   * Service method. Allows quickly investigate test cases failed directly on TeamCity agent.
   * Agent output should be placed in test data directory and could be checked against required output
  */
  def testServerLogs(): Unit = {
    val workingDir = new File(IntegrationTestLayout.repoRoot(), "test/testdata/0.13/multiproject")
    val requiredFile = new File(workingDir, "output.txt")
    val serverLogs = new File(workingDir, "server_logs.log")
    SbtOutputVerifier.checkOutputFile(serverLogs, excludesFile = None, requiredFiles = Seq(requiredFile))
  }
}
