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
    runCase(SbtLoggerOutputTestCase("compileerror", Seq("sbt-teamcity-logger"), outputFiles = Seq("plugin_status_output.txt")))

  @Test
  def testNonTeamCityMode(): Unit =
    // Keep this shared: the plugin must remain silent outside TeamCity on every supported SBT version.
    runCase(
      SbtLoggerOutputTestCase(
        "compileerror",
        Seq("sbt-teamcity-logger"),
        outputFiles = Seq("plugin_status_non_teamcity_output.txt"),
        teamCityEnvironment = false,
        expectNoTeamCityMessages = true
      )
    )

  @Test
  def testCompileErrorOutput(): Unit =
    runCase(SbtLoggerOutputTestCase("compileerror", Seq("compile")))

  @Test
  def testCompileSuccessfulOutput(): Unit =
    runCase(SbtLoggerOutputTestCase("compilesuccessful", Seq("compile")))

  @Test
  def testMultiProjectsOutput(): Unit =
    runCase(SbtLoggerOutputTestCase("multiproject", Seq("compile")))

  @Test
  def testTmp(): Unit =
    runCase(SbtLoggerOutputTestCase("multiproject", Seq("compile"), sbtOptions = Seq("--debug")))

  @Test
  def testScalaTest(): Unit =
    runCase(SbtLoggerOutputTestCase(
      "testsupport/scalatest",
      Seq("test"),
      outputFiles = Seq("output.txt", "output1.txt"),
      expectZeroExitCode = true
    ))

  @Test
  def testNoSbtFileInProject(): Unit =
    runCase(SbtLoggerOutputTestCase("nosbtfile", Seq("compile")))

  @Test
  def testJUnit(): Unit =
    runCase(SbtLoggerOutputTestCase("testsupport/junit", Seq("test"), outputFiles = Seq("output.txt"), expectZeroExitCode = true))

  @Test
  def testWarningInspectionsInCompile(): Unit =
    runCase(SbtLoggerOutputTestCase("compileInspections", Seq("clean", "compile"), sbtOptions = Seq.empty))

  @Test
  def testWarningInTestOutput(): Unit =
    runCase(SbtLoggerOutputTestCase("TW35693", Seq("test")))

  @Test
  def testTW35404_error(): Unit =
    runCase(SbtLoggerOutputTestCase("TW35404_error", Seq("compile")))

  @Test
  def testTW35404_debug(): Unit =
    runCase(SbtLoggerOutputTestCase("TW35404_debug", Seq("compile")))

  @Test
  def testSubProject_compile(): Unit =
    runCase(SbtLoggerOutputTestCase("subproject", Seq("backend/compile")))

  @Test
  def testRunTestWithSbt(): Unit =
    runCase(
      SbtLoggerOutputTestCase(
        "testsupport/scalatest",
        Seq("test"),
        outputFiles = Seq("output.txt", "output1.txt"),
        expectZeroExitCode = true
      )
    )

  @Test
  def testOtherSbtVersions(): Unit =
    runCase(SbtLoggerOutputTestCase("otherVersions", Seq("sbtVersion"), sbtOptions = Seq("--info")))

  @Test
  def testProjectWithJavaSources(): Unit =
    runCase(
      SbtLoggerOutputTestCase(
        "withJavaSources",
        Seq("clean", "compile", "run"),
        sbtOptions = Seq("--debug"),
        outputFiles = Seq("output.txt")
      )
    )

  @Test
  def testIgnoredTest(): Unit =
    runCase(SbtLoggerOutputTestCase("ignoredTest", Seq("test"), sbtOptions = Seq("--info")))

  @Test
  def testNestedSuites(): Unit =
    runCase(SbtLoggerOutputTestCase("testsupport/nested", Seq("test"), sbtOptions = Seq("--info")))

  @Test
  def testSpecTW46964(): Unit =
    runCase(SbtLoggerOutputTestCase("testsupport/scalatest_TW46964", Seq("testOnly"), outputFiles = Seq("output.txt")))

  @Test
  def testSpec2(): Unit =
    runCase(SbtLoggerOutputTestCase("testsupport/spec2", Seq("testOnly"), outputFiles = Seq("output.txt")))

  @Test
  def testTW50753_initErrorInTests(): Unit =
    runCase(SbtLoggerOutputTestCase("TW-50753_initErrorInTests", Seq("clean", "compile", "test"), outputFiles = Seq("output.txt")))

  @Test
  def testParallelTestExecutionTW43578(): Unit =
    runCase(
      SbtLoggerOutputTestCase(
        "testsupport/parallelTestExecutionTW43578/src/",
        Seq("test"),
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
      )
    )
}
