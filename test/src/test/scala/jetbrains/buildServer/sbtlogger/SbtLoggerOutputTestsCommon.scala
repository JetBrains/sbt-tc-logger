package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.SbtLoggerOutputTestCase
import org.junit.Test

/**
 * Common JUnit scenarios for sbt TeamCity logger output integration tests.
 *
 * Each `@Test` method keeps the fixture details next to the test name and delegates to [[runCase]], which performs the
 * actual nested sbt execution and output verification. Runtime-specific suites may inherit these tests, ignore selected
 * ones, or add their own scenarios next to their runtime-specific fixtures.
 *
 * @param runtime sbt runtime used by every case in this suite instance.
 */
abstract class SbtLoggerOutputTestsCommon(runtime: SbtTestsRuntime) extends SbtLoggerOutputTestBase(runtime) {

  @Test
  def testPluginStatus(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compileerror",
      sbtCommands = Seq("sbt-teamcity-logger"),
      outputFiles = Seq("plugin_status_output.txt")
    ))

  @Test
  def testNonTeamCityMode(): Unit =
    // Keep this shared: the plugin must remain silent outside TeamCity on every supported SBT version.
    runCase(SbtLoggerOutputTestCase(
      fixture = "compileerror",
      sbtCommands = Seq("sbt-teamcity-logger"),
      outputFiles = Seq("plugin_status_non_teamcity_output.txt"),
      teamCityEnvironment = false,
      expectNoTeamCityMessages = true
    ))

  @Test
  def testCompileErrorOutput(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compileerror",
      sbtCommands = Seq("compile")
    ))

  @Test
  def testCompileSuccessfulOutput(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilesuccessful",
      sbtCommands = Seq("compile")
    ))

  @Test
  def testMultiProjectsOutput(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "multiproject",
      sbtCommands = Seq("compile")
    ))

  @Test
  def testTmp(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "multiproject",
      sbtCommands = Seq("compile"),
      sbtOptions = Seq("--debug")
    ))

  @Test
  def testNoSbtFileInProject(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "nosbtfile",
      sbtCommands = Seq("compile"),
      expectZeroExitCode = true
    ))

  @Test
  def testJUnit(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testsupport/junit",
      sbtCommands = Seq("test"),
      outputFiles = Seq("output.txt"),
      expectZeroExitCode = true
    ))

  @Test
  def testWarningInspectionsInCompile(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compileInspections",
      sbtCommands = Seq("clean", "compile"),
      sbtOptions = Seq.empty
    ))

  @Test
  def testWarningInTestOutput(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "TW35693",
      sbtCommands = Seq("test"),
      expectZeroExitCode = true
    ))

  @Test
  def testTW35404_error(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "TW35404_error",
      sbtCommands = Seq("compile")
    ))

  @Test
  def testTW35404_debug(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "TW35404_debug",
      sbtCommands = Seq("compile")
    ))

  @Test
  def testSubProject_compile(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "subproject",
      sbtCommands = Seq("backend/compile")
    ))

  @Test
  def testRunTestWithSbt(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testsupport/scalatest",
      sbtCommands = Seq("test"),
      outputFiles = Seq("output.txt", "output1.txt"),
      expectZeroExitCode = true
    ))

  @Test
  def testProjectWithJavaSources(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "withJavaSources",
      sbtCommands = Seq("clean", "compile", "run"),
      sbtOptions = Seq("--debug"),
      outputFiles = Seq("output.txt")
    ))

  @Test
  def testIgnoredTest(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "ignoredTest",
      sbtCommands = Seq("test"),
      sbtOptions = Seq("--info"),
      expectZeroExitCode = true
    ))

  @Test
  def testNestedSuites(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testsupport/nested",
      sbtCommands = Seq("test"),
      sbtOptions = Seq("--info"),
      expectZeroExitCode = true
    ))

  @Test
  def testSpecTW46964(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testsupport/scalatest_TW46964",
      sbtCommands = Seq("testOnly"),
      outputFiles = Seq("output.txt")
    ))

  @Test
  def testSpec2(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testsupport/spec2",
      sbtCommands = Seq("testOnly"),
      outputFiles = Seq("output.txt"),
      expectZeroExitCode = true
    ))

  @Test
  def testParallelTestExecutionTW43578(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testsupport/parallelTestExecutionTW43578/src/",
      sbtCommands = Seq("test"),
      sbtOptions = Seq("--info"),
      outputFiles = Seq(
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
    ))
}
