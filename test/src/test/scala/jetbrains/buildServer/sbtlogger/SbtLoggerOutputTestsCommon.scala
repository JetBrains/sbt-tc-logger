package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.SbtLoggerOutputTestCase
import org.junit.Assume.assumeFalse
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

  // Verifies that the plugin reports loading and detects the TeamCity version.
  @Test
  def pluginStatus_LoadedInTeamCity(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/failure",
      sbtCommands = Seq("sbt-teamcity-logger"),
      outputFiles = Seq("plugin_status_output.txt")
    ))

  // Verifies that the plugin disables itself and emits no service messages outside TeamCity.
  @Test
  def pluginStatus_DisabledOutsideTeamCity(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/failure",
      sbtCommands = Seq("sbt-teamcity-logger"),
      outputFiles = Seq("plugin_status_non_teamcity_output.txt"),
      teamCityEnvironment = false,
      expectNoTeamCityMessages = true
    ))

  // Verifies compiler lifecycle, source-error, and final-failure messages for a failed compilation.
  @Test
  def compilation_FailureReported(): Unit = {
    // SBT 1.0.0 cannot start its x86-only JNA socket server on a native Apple Silicon JVM.
    assumeFalse(
      "SBT 1.0.0 requires x86_64 Java under Rosetta or an x86 CI agent on Apple Silicon hosts.",
      runtime == SbtTestsRuntime.Sbt_1_0_0 && isNativeAppleSilicon
    )

    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/failure",
      sbtCommands = Seq("compile")
    ))
  }

  // Verifies compiler start and finish messages for a successful Scala compilation.
  @Test
  def compilation_SuccessReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/success",
      sbtCommands = Seq("compile")
    ))

  // Verifies that compilation failures from both aggregated subprojects are reported.
  @Test
  def compilation_MultiProject_FailuresReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/multiProject",
      sbtCommands = Seq("compile")
    ))

  // Verifies that multi-project compilation failures remain reported with the SBT debug option.
  @Test
  def compilation_MultiProject_FailuresReportedWithDebug(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/multiProject",
      sbtCommands = Seq("compile"),
      sbtOptions = Seq("--debug")
    ))

  // Verifies that a project without build.sbt compiles successfully and reports its compiler lifecycle.
  @Test
  def projectConfiguration_NoBuildFileCompiles(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "projectConfiguration/noBuildFile",
      sbtCommands = Seq("compile"),
      expectZeroExitCode = true
    ))

  // Verifies JUnit suite, passing-test, failing-test, and failure-detail service messages.
  @Test
  def testReporting_JUnit_PassAndFailureReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/JUnit_PassAndFailure",
      sbtCommands = Seq("test"),
      outputFiles = Seq("output.txt"),
      expectZeroExitCode = true
    ))

  // TW-53224 - `testQuick` must preserve the normal per-test TeamCity protocol, including failures.
  // A deliberately failing JUnit method proves the task uses the logger's silent result handler instead of only
  // exercising an empty quick-test selection.
  @Test
  def testReporting_JUnit_TestQuickPassAndFailureReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/JUnit_PassAndFailure",
      sbtCommands = Seq("testQuick"),
      outputFiles = Seq("output.txt"),
      expectZeroExitCode = true
    ))

  // Verifies that compiler warnings are reported as TeamCity warning inspections.
  @Test
  def compilation_WarningsReportedAsInspections(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/warnings",
      sbtCommands = Seq("clean", "compile"),
      sbtOptions = Seq.empty
    ))

  // TW-35693 - Verifies that error-like ScalaTest output is not reported as a compilation failure.
  @Test
  def testReporting_ScalaTest_ErrorLikeOutputNotCompilationFailure(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/ScalaTest_ErrorLikeOutputNotCompilationFailure",
      sbtCommands = Seq("test"),
      // SBT 2 may schedule the main and test compilation lifecycles in either order.
      // Verify both lifecycles independently while preserving their own start/finish order.
      outputFiles = Seq("compilation-output.txt", "test-compilation-output.txt", "output.txt"),
      expectZeroExitCode = true
    ))

  // TW-35404 - Verifies that error-level logging suppresses compiler debug noise.
  @Test
  def compilerLogLevel_DebugOutputSuppressedAtError(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilerLogLevel/error",
      sbtCommands = Seq("compile")
    ))

  // TW-35404 - Verifies that debug-level logging keeps compiler debug output visible.
  @Test
  def compilerLogLevel_DebugOutputShownAtDebug(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilerLogLevel/debug",
      sbtCommands = Seq("compile")
    ))

  // Verifies compiler lifecycle messages for the backend subproject compile command.
  @Test
  def compilation_SubprojectLifecycleReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/subproject",
      sbtCommands = Seq("backend/compile")
    ))

  // Verifies standard ScalaTest passing and failing test service messages.
  @Test
  def testReporting_ScalaTest_PassAndFailureReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/ScalaTest_PassAndFailure",
      sbtCommands = Seq("test"),
      outputFiles = Seq("output.txt", "output1.txt"),
      expectZeroExitCode = true
    ))

  // Verifies that mixed Java and Scala sources compile and the Java main class runs.
  @Test
  def projectExecution_JavaSourcesCompileAndRun(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "projectExecution/javaSources",
      sbtCommands = Seq("clean", "compile", "run"),
      sbtOptions = Seq("--debug"),
      outputFiles = Seq("output.txt")
    ))

  // Verifies that framework-skipped Specs2 examples are reported as ignored tests.
  @Test
  def testReporting_Specs2_IgnoredTestsReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/Specs2_IgnoredTests",
      sbtCommands = Seq("test"),
      sbtOptions = Seq("--info"),
      expectZeroExitCode = true
    ))

  // Verifies nested ScalaTest suite and member-test service messages.
  @Test
  def testReporting_ScalaTest_NestedSuitesReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/ScalaTest_NestedSuites",
      sbtCommands = Seq("test"),
      sbtOptions = Seq("--info"),
      expectZeroExitCode = true
    ))

  // TW-46964 - Verifies that long ScalaTest FeatureSpec names do not repeat name segments.
  @Test
  def testReporting_ScalaTest_LongNamesNotDuplicated(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/ScalaTest_LongNamesNotDuplicated",
      sbtCommands = Seq("testOnly"),
      outputFiles = Seq("output.txt")
    ))

  // Verifies that Specs2 examples invoked through testOnly are reported.
  @Test
  def testReporting_Specs2_TestOnlyExamplesReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/Specs2_TestOnlyExamples",
      sbtCommands = Seq("testOnly"),
      outputFiles = Seq("output.txt"),
      expectZeroExitCode = true
    ))

  // TW-43578 - Verifies parallel and non-parallel ScalaTest test and suite event reporting.
  @Test
  def testReporting_ScalaTest_ParallelEventsReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/ScalaTest_ParallelEvents",
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

  private def isNativeAppleSilicon: Boolean = {
    val osName = System.getProperty("os.name", "").toLowerCase
    val osArchitecture = System.getProperty("os.arch", "").toLowerCase
    osName.contains("mac") &&
      Set("aarch64", "arm64").contains(osArchitecture)
  }
}
