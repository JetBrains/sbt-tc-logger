package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.{ExpectationSet, SbtCompilationLifecycleExpectation, SbtDependencyLifecycleExpectation, SbtExitCodeExpectation, SbtFailurePropagationExpectation, SbtLoggerOutputTestCase, SbtOutputExpectations}
import org.junit.Test

/**
 * Common JUnit scenarios for sbt TeamCity logger output integration tests.
 *
 * Each `@Test` method keeps the fixture details next to the test name and delegates to [[runCase]], which performs the
 * actual nested sbt execution and output verification. Runtime-specific suites may inherit these tests or add
 * capability-specific scenarios through a dedicated trait.
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
      expectations = ExpectationSet.singleFile("plugin_status_output.txt")
    ))

  // Verifies that the plugin disables itself and emits no service messages outside TeamCity.
  @Test
  def pluginStatus_DisabledOutsideTeamCity(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/failure",
      sbtCommands = Seq("sbt-teamcity-logger"),
      expectations = ExpectationSet.singleFile("plugin_status_non_teamcity_output.txt"),
      teamCityEnvironment = false,
      expectNoTeamCityMessages = true
    ))

  // Outside TeamCity the plugin must leave normal task logging untouched.
  @Test
  def taskLogging_CompileStaysInactiveOutsideTeamCity(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/success",
      sbtCommands = Seq("clean", "compile"),
      sbtOptions = Seq("--info"),
      verifyOutput = false,
      expectedExitCode = SbtExitCodeExpectation.Zero,
      teamCityEnvironment = false,
      expectNoTeamCityMessages = true
    ))

  // Outside TeamCity SBT's original test-result logger must still make failed tests fail the process.
  @Test
  def testReporting_FailedTestsStillFailOutsideTeamCity(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/JUnit_PassAndFailure",
      sbtCommands = Seq("test"),
      verifyOutput = false,
      expectedExitCode = SbtExitCodeExpectation.NonZero,
      teamCityEnvironment = false,
      expectNoTeamCityMessages = true
    ))

  // Verifies compiler lifecycle, source-error, and final-failure messages for a failed compilation.
  @Test
  def compilation_FailureReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/failure",
      sbtCommands = Seq("compile"),
      failurePropagation = compilationFailurePropagation,
      compilationLifecycle = compilationFailureLifecycle(expectedClosures = 1)
    ))

  // Verifies compiler start and finish messages for a successful Scala compilation.
  @Test
  def compilation_SuccessReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/success",
      sbtCommands = Seq("compile")
    ))

  // `update` is independently invokable: it gets a resolver block, never a fake compiler lifecycle.
  @Test
  def dependencyResolution_DirectUpdateReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/success",
      sbtCommands = Seq("clean", "update"),
      sbtOptions = Seq("--info"),
      expectations = ExpectationSet.singleFile("direct-update-output.txt"),
      dependencyLifecycle = Some(SbtDependencyLifecycleExpectation.Complete(expectedClosures = 1)),
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Absent)
    ))

  // `compileInputs` prepares compilation inputs but must not open the compiler block itself.
  @Test
  def compilation_DirectCompileInputsDoesNotInventCompilerLifecycle(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/success",
      sbtCommands = Seq("clean", "compileInputs"),
      sbtOptions = Seq("--info"),
      verifyOutput = false,
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Absent)
    ))

  // Resolver failures close only their dependency block and remain independent from compiler error reporting.
  @Test
  def dependencyResolution_UpdateFailureClosesItsBlock(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "dependencyResolution/updateFailure",
      sbtCommands = Seq("update"),
      sbtOptions = Seq("--info"),
      failurePropagation = SbtFailurePropagationExpectation.ProcessExitNonZero,
      dependencyLifecycle = Some(SbtDependencyLifecycleExpectation.Complete(expectedClosures = 1)),
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Absent)
    ))

  // Ordinary logger levels are emitted exactly once through the TeamCity screen appender.
  @Test
  def taskLogging_GenericLevelsAreSingleStructuredMessages(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "logging/genericLevels",
      sbtCommands = Seq("genericLevels"),
      sbtOptions = Seq("--debug")
    ))

  // The replacement policy is strict by default: a build's custom manager does not leak duplicate console messages.
  @Test
  def taskLogging_CustomLogManagerIsReplacedByDefault(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "logging/customLogManager",
      sbtCommands = Seq("customManagerLog"),
      sbtOptions = Seq("--info"),
      expectations = ExpectationSet.singleFile("strict-output.txt")
    ))

  // The documented JVM property is the only fallback: it preserves the custom manager and deliberately stops
  // mirroring ordinary task logger messages into TeamCity.
  @Test
  def taskLogging_CustomLogManagerCanBePreservedExplicitly(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "logging/customLogManager",
      sbtCommands = Seq("customManagerLog"),
      sbtOptions = Seq("--info", "-Dteamcity.sbt.logger.preserveConsole=true"),
      verifyOutput = false,
      expectNoTeamCityMessages = true
    ))

  // Observer mode must keep the default compiler reporter while still publishing inspections, without compiler blocks.
  @Test
  def compilation_MinimalModePreservesDefaultOutputAndReportsInspections(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "logging/preserveConsole",
      sbtCommands = Seq("compile"),
      sbtOptions = Seq("-Dteamcity.sbt.logger.preserveConsole=true"),
      failurePropagation = compilationFailurePropagation,
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Absent)
    ))

  // The default test-result logger is retained in observer mode, while the TeamCity test listener remains active.
  @Test
  def testReporting_MinimalModePreservesDefaultResultLoggerAndReportsEvents(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/JUnit_PassAndFailure",
      sbtCommands = Seq("test"),
      sbtOptions = Seq("-Dteamcity.sbt.logger.preserveConsole=true"),
      expectations = ExpectationSet.singleFile("minimal-output.txt"),
      expectedExitCode = SbtExitCodeExpectation.NonZero,
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Absent)
    ))

  // Verifies that compilation failures from both aggregated subprojects are reported.
  @Test
  def compilation_MultiProject_FailuresReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/multiProject",
      sbtCommands = Seq("compile"),
      // The fixture asserts its own ordered subsequence; its scheduler-dependent three-flow topology is checked by the lifecycle contract.
      expectations = SbtOutputExpectations.multiProjectCompilation,
      failurePropagation = compilationFailurePropagation,
      compilationLifecycle = compilationFailureLifecycle(expectedClosures = 3)
    ))

  // Verifies that multi-project compilation failures and all compiler lifecycles remain reported with the SBT debug option.
  @Test
  def compilation_MultiProject_FailuresReportedWithDebug(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/multiProject",
      sbtCommands = Seq("compile"),
      sbtOptions = Seq("--debug"),
      expectations = SbtOutputExpectations.multiProjectCompilation,
      failurePropagation = compilationFailurePropagation,
      compilationLifecycle = compilationFailureLifecycle(expectedClosures = 3)
    ))

  // Verifies Test / compile closes its lifecycle and rethrows an underlying compiler failure.
  @Test
  def testCompilation_FailureReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/testFailure",
      sbtCommands = Seq(testCompileCommand),
      failurePropagation = compilationFailurePropagation,
      compilationLifecycle = testCompilationFailureLifecycle
    ))

  // Verifies that a project without build.sbt compiles successfully and reports its compiler lifecycle.
  @Test
  def projectConfiguration_NoBuildFileCompiles(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "projectConfiguration/noBuildFile",
      sbtCommands = Seq("compile"),
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))

  // Verifies JUnit suite, passing-test, failing-test, and failure-detail service messages.
  @Test
  def testReporting_JUnit_PassAndFailureReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/JUnit_PassAndFailure",
      sbtCommands = Seq("test"),
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))

  // TW-53224 - `testQuick` must preserve the normal per-test TeamCity protocol, including failures.
  // A deliberately failing JUnit method proves the task uses the logger's silent result handler instead of only
  // exercising an empty quick-test selection.
  @Test
  def testReporting_JUnit_TestQuickPassAndFailureReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/JUnit_PassAndFailure",
      sbtCommands = Seq("testQuick"),
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))

  // Verifies that compiler warnings are reported as TeamCity warning inspections.
  @Test
  def compilation_WarningsReportedAsInspections(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/warnings",
      sbtCommands = Seq("clean", "compile"),
      sbtOptions = Seq.empty
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
      expectations = SbtOutputExpectations.scalaTestPassAndFailure,
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))

  // Verifies that mixed Java and Scala sources compile and the Java main class runs.
  @Test
  def projectExecution_JavaSourcesCompileAndRun(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "projectExecution/javaSources",
      sbtCommands = Seq("clean", "compile", "run"),
      sbtOptions = Seq("--debug"),
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Complete(expectedClosures = 2)),
    ))

  // Verifies that framework-skipped Specs2 examples are reported as ignored tests.
  @Test
  def testReporting_Specs2_IgnoredTestsReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/Specs2_IgnoredTests",
      sbtCommands = Seq("test"),
      sbtOptions = Seq("--info"),
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))

  // Verifies nested ScalaTest suite and member-test service messages.
  @Test
  def testReporting_ScalaTest_NestedSuitesReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/ScalaTest_NestedSuites",
      sbtCommands = Seq("test"),
      sbtOptions = Seq("--info"),
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))

  // TW-46964 - Verifies that long ScalaTest FeatureSpec names do not repeat name segments.
  @Test
  def testReporting_ScalaTest_LongNamesNotDuplicated(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/ScalaTest_LongNamesNotDuplicated",
      sbtCommands = Seq("testOnly")
    ))

  // Verifies that Specs2 examples invoked through testOnly are reported.
  @Test
  def testReporting_Specs2_TestOnlyExamplesReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/Specs2_TestOnlyExamples",
      sbtCommands = Seq("testOnly"),
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))

  // TW-43578 - Verifies parallel and non-parallel ScalaTest test and suite event reporting.
  @Test
  def testReporting_ScalaTest_ParallelEventsReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/ScalaTest_ParallelEvents",
      sbtCommands = Seq("test"),
      sbtOptions = Seq("--info"),
      expectations = SbtOutputExpectations.scalaTestParallelEvents
    ))

  private def compilationFailureLifecycle(expectedClosures: Int): Option[SbtCompilationLifecycleExpectation] =
    Some(SbtCompilationLifecycleExpectation.Complete(expectedClosures))

  private def testCompilationFailureLifecycle: Option[SbtCompilationLifecycleExpectation] =
    Some(SbtCompilationLifecycleExpectation.Complete(expectedClosures = 2))

  private def testCompileCommand: String = "Test / compile"

  private def compilationFailurePropagation: SbtFailurePropagationExpectation =
    SbtFailurePropagationExpectation.ProcessExitNonZero

  /**
   * Aggregate projects may run in parallel, so their output fixture intentionally does not impose a false total order.
   * The lifecycle verifier instead associates each summary with its own complete flow.
   */
}
