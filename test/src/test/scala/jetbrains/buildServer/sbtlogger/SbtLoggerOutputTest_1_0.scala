package jetbrains.buildServer.sbtlogger

import junit.framework.Assert
import org.junit.Test

import java.io.File

class SbtLoggerOutputTest_1_0 {

  @Test
  def testCompileErrorOutput(): Unit = {
    SbtProcess.runAndTest("compile", testPath("compileerror"))
  }

  @Test
  def testPluginStatus(): Unit = {
    SbtProcess.runAndTest("sbt-teamcity-logger", testPath("compileerror"), "plugin_status_output.txt")
  }

  @Test
  def testCompileSuccessfulOutput(): Unit = {
    SbtProcess.runAndTest("compile", testPath("compilesuccessful"))
  }

  @Test
  def testMultiProjectsOutput(): Unit = {
    SbtProcess.runAndTest("compile", testPath("multiproject"))
  }

  @Test
  def testTmp(): Unit = {
    SbtProcess.runAndTestWithAdditionalParams("--debug", "compile", testPath("multiproject"))
  }

  @Test
  def testJUnit(): Unit = {
    val exitCode = SbtProcess.runAndTest("test", testPath("testsupport/junit"), "output.txt")
    // if need exit code equals 0, otherwise in TeamCity additional non-informative build problem message will appear
    Assert.assertEquals(0, exitCode)
  }

  /*
  @Test
  def testScalaTest(): Unit = {
    val exitCode = SbtProcess.runAndTest("test", testPath("testsupport/scalatest"), "output.txt", "output1.txt")
    // if need exit code equals 0, otherwise in TeamCity additional non-informative build problem message will appear
    Assert.assertEquals(0, exitCode)
  }
   */

  @Test
  def testWarningInspectionsInCompile(): Unit = {
    SbtProcess.runAndTestWithAdditionalParams("clean compile", "", testPath("compileInspections"))
  }

  // todo[shkate]
  def testWarningInTestOutput(): Unit = {
    SbtProcess.runAndTest("test", testPath("TW35693"))
  }

  @Test
  def testTW35404_error(): Unit = {
    SbtProcess.runAndTest("compile", testPath("TW35404_error"))
  }

  @Test
  def testTW35404_debug(): Unit = {
    SbtProcess.runAndTest("compile", testPath("TW35404_debug"))
  }

  @Test
  def testSubProject_compile(): Unit = {
    SbtProcess.runAndTest("backend/compile", testPath("subproject"))
  }

  @Test
  def testRunTestWithSbt(): Unit = {
    val exitCode = SbtProcess.runAndTest("test", testPath("testsupport/scalatest"), "output.txt", "output1.txt")
    // if need exit code equals 0, otherwise in TeamCity additional non-informative build problem message will appear
    Assert.assertEquals(0, exitCode)
  }

  @Test
  def testProjectWithJavaSources(): Unit = {
    SbtProcess.runAndTestWithAdditionalParams("clean compile run", "--debug", testPath("withJavaSources"), "output.txt")
  }

  // todo[shkate]
  def testIgnoredTest(): Unit = {
    SbtProcess.runAndTestWithAdditionalParams("--info", "test", testPath("ignoredTest"))
  }

  // todo[shkate]
  def testNestedSuites(): Unit = {
    SbtProcess.runAndTestWithAdditionalParams("--info", "test", testPath("testsupport/nested"))
  }

  @Test
  def testSpecTW46964(): Unit = {
    SbtProcess.runAndTest("testOnly", testPath("testsupport/scalatest_TW46964"), "output.txt")
  }

  // todo[shkate]
  def testSpec2(): Unit = {
    SbtProcess.runAndTest("testOnly", testPath("testsupport/spec2"), "output.txt")
  }

  // todo[shkate]
  def testTW50753_initErrorInTests(): Unit = {
    SbtProcess.runAndTest("clean compile test", testPath("TW-50753_initErrorInTests"), "output.txt")
  }

  @Test
  def testParallelTestExecutionTW43578(): Unit = {
    SbtProcess.runAndTestWithAdditionalParams(
      "--info",
      "test",
      testPath("testsupport/parallelTestExecutionTW43578/src/"),
      "output.txt",
      "output1.txt",
      "output2.txt",
      "output3.txt",
      "output4.txt",
      "output5.txt",
      "output7.txt",
      "output6.txt",
      "output8.txt",
      "output9.txt",
      "output10.txt",
      "output11.txt"
    )
  }

  private def testPath(testRepo: String): String = {
    val testDir = new File(new File(SbtProcess.repoRoot(), "test/testdata/1.0"), testRepo)
    testDir.getAbsolutePath
  }
}
