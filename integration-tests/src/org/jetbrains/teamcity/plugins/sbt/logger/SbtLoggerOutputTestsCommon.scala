package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.{
  SbtLoggerOutputTestCase,
  SbtOutputVerificationSelection,
  SbtProcessResultExpectation
}
import org.junit.Test

/** Common output-verification scenarios shared by every supported SBT runtime; exact remains the default. */
abstract class SbtLoggerOutputTestsCommon(runtime: SbtTestsRuntime) extends SbtLoggerOutputTestBase(runtime) {

  @Test def pluginStatus_LoadedInTeamCity(): Unit = run(
    "plugin-status-active", "compilation/failure", behavior = Seq("sbt-teamcity-logger"), success = true,
    verification = ExactCanary)

  @Test def pluginStatus_ReportsConfiguredLoggerOptions(): Unit = run(
    "plugin-status-configured", "compilation/failure",
    behavior = Seq("sbt-teamcity-logger"), success = true,
    options = Seq(
      "-Dteamcity.sbt.logger.preserveConsole=true",
      "-Dteamcity.sbt.logger.useTeamCityTestResultLogger=false",
      "-Dteamcity.sbt.logger.showTestTaskOutput=false",
      "-Dteamcity.sbt.logger.detailedDependencyResolution=true",
      "-Dteamcity.sbt.logger.renderObjectEventDetails=true"
    ), verification = ExactCanary)

  @Test def pluginStatus_DisabledOutsideTeamCity(): Unit = run(
    "plugin-status-outside-teamcity", "compilation/failure",
    behavior = Seq("sbt-teamcity-logger"), success = true, teamCity = false,
    verification = ExactCanary)

  @Test def taskLogging_CompileStaysInactiveOutsideTeamCity(): Unit = run(
    "compile-outside-teamcity", "compilation/success",
    setup = Seq("clean"), behavior = Seq("compile"), success = true,
    options = Seq("--info"), teamCity = false, verification = ExactCanary)

  @Test def testReporting_FailedTestsStillFailOutsideTeamCity(): Unit = run(
    "failed-tests-outside-teamcity", "testSupport/JUnit_PassAndFailure",
    behavior = Seq("test"), success = false, teamCity = false,
    verification = ExactCanary)

  @Test def compilation_FailureReported(): Unit = run(
    "compilation-failure", "compilation/failure", behavior = Seq("compile"), success = false,
    verification = SbtOrdinaryCompilationSemanticContracts.Failure)

  @Test def compilation_SuccessReported(): Unit = run(
    "compilation-success", "compilation/success",
    setup = Seq("clean"), behavior = Seq("compile"), success = true, options = Seq("--info"),
    verification = SbtOrdinaryCompilationSemanticContracts.Success)

  @Test def compilation_DirectCompileIncrementalReported(): Unit = run(
    "compile-incremental", "compilation/success",
    setup = Seq("clean"), behavior = Seq("Compile / compileIncremental"), success = true, options = Seq("--info"),
    verification = SbtOrdinaryCompilationSemanticContracts.Incremental)

  @Test def compilation_UpToDateInfoDoesNotCreateEmptyBlock(): Unit = run(
    "compilation-up-to-date", "compilation/success",
    setup = Seq("clean"), behavior = Seq("compile", "compile"), success = true, options = Seq("--info"),
    verification = SbtOrdinaryCompilationSemanticContracts.UpToDate)

  // A direct update stays in the ordinary corpus: its default-deny contract proves detailed reporting remains absent.
  @Test def dependencyResolution_DirectUpdateIsSilentByDefault(): Unit = run(
    "dependency-update-default", "compilation/success",
    setup = Seq("clean"), behavior = Seq("update"), success = true, options = Seq("--info"),
    verification = SbtDependencyDetailedOutcomesSemanticContracts.DefaultUpdate)

  @Test def compilation_DirectCompileInputsDoesNotInventCompilerLifecycle(): Unit = run(
    "compile-inputs", "compilation/success",
    behavior = Seq("Compile / dependencyClasspath"), success = true, options = Seq("--info"),
    verification = SbtOrdinaryCompilationSemanticContracts.Inputs)

  @Test def dependencyResolution_UpdateFailureIsSilentByDefault(): Unit = run(
    "dependency-update-failure-default", "dependencyResolution/updateFailure",
    behavior = Seq("update"), success = false, options = Seq("--info"),
    verification = SbtDependencyDetailedOutcomesSemanticContracts.DefaultUpdateFailure)

  @Test def taskLogging_GenericLevelsAreSingleStructuredMessages(): Unit = run(
    "logging-generic-levels", "logging/genericLevels",
    behavior = Seq("genericLevels"), success = true, options = Seq("--debug"),
    verification = ExactCanary)

  @Test def taskLogging_CustomLogManagerIsReplacedByDefault(): Unit = run(
    "logging-custom-manager-replaced", "logging/customLogManager",
    behavior = Seq("customManagerLog"), success = true, options = Seq("--info"),
    verification = ExactCanary)

  @Test def taskLogging_CustomLogManagerCanBePreservedExplicitly(): Unit = run(
    "logging-custom-manager-preserved", "logging/customLogManager",
    behavior = Seq("customManagerLog"), success = true,
    options = Seq("--info", "-Dteamcity.sbt.logger.preserveConsole=true"),
    verification = ExactCanary)

  @Test def compilation_MinimalModePreservesDefaultOutputAndReportsInspections(): Unit = run(
    "compilation-preserve-console", "logging/preserveConsole",
    behavior = Seq("compile"), success = false,
    options = Seq("-Dteamcity.sbt.logger.preserveConsole=true"),
    verification = SbtCompilationOutputSemanticContracts.PreserveConsole)

  @Test def testReporting_MinimalModePreservesDefaultResultLoggerAndReportsEvents(): Unit = run(
    "tests-preserve-console", "testSupport/JUnit_PassAndFailure",
    behavior = Seq("test"), success = false,
    options = Seq(
      "-Dteamcity.sbt.logger.preserveConsole=true",
      "-Dteamcity.sbt.logger.showTestTaskOutput=false"
    ), verification = ExactCanary)

  // Aggregate compiles are genuinely concurrent; the semantic contract deliberately has no cross-project edge.
  @Test def compilation_MultiProject_FailuresReported(): Unit = run(
    "compilation-multiproject-failure", "compilation/multiProject",
    behavior = Seq("compile"), success = false,
    verification = SbtMultiProjectSemanticContracts.Failure)

  @Test def compilation_MultiProject_FailuresReportedWithDebug(): Unit = run(
    "compilation-multiproject-failure-debug", "compilation/multiProject",
    behavior = Seq("compile"), success = false, options = Seq("--debug"),
    verification = SbtMultiProjectSemanticContracts.FailureDebug)

  @Test def testCompilation_ConcurrentProjectsKeepMainBeforeOwnTest(): Unit = run(
    "compilation-concurrent-main-test", "compilation/concurrentMainTest",
    behavior = Seq("Test / compile"), success = true, options = Seq("--info"),
    verification = SbtConcurrentMainTestSemanticContracts.Success)

  @Test def testCompilation_MainCompletesBeforeFailureIsReported(): Unit = run(
    "test-compilation-failure", "compilation/testFailure",
    behavior = Seq("Test / compile"), success = false, options = Seq("--info"),
    verification = SbtCompilationOutputSemanticContracts.TestCompilationFailure)

  @Test def projectConfiguration_NoBuildFileCompiles(): Unit = run(
    "project-no-build-file", "projectConfiguration/noBuildFile",
    behavior = Seq("compile"), success = true,
    verification = SbtOrdinaryCompilationSemanticContracts.NoBuildFile)

  @Test def testReporting_JUnit_PassAndFailureReported(): Unit = run(
    "junit-pass-and-failure", "testSupport/JUnit_PassAndFailure",
    behavior = Seq("test"), success = false,
    verification = SbtJUnitTaskSemanticContracts.PassAndFailure)

  @Test def testReporting_JUnit_TestQuickPassAndFailureReported(): Unit = run(
    "junit-test-quick", "testSupport/JUnit_PassAndFailure",
    behavior = Seq("testQuick"), success = false,
    verification = SbtJUnitTaskSemanticContracts.TestQuick)

  @Test def testReporting_JUnit_TestOnlyPassAndFailureReported(): Unit = run(
    "junit-test-only", "testSupport/JUnit_PassAndFailure",
    behavior = Seq("testOnly thisis.a.test.ATest"), success = false,
    verification = SbtJUnitTaskSemanticContracts.TestOnly)

  @Test def testReporting_TeamCityResultLoggerCanHideTestTaskOutput(): Unit = run(
    "junit-teamcity-result-no-task-output", "testSupport/JUnit_PassAndFailure",
    behavior = Seq("test"), success = false,
    options = Seq("-Dteamcity.sbt.logger.showTestTaskOutput=false"),
    verification = SbtJUnitResultLoggerSemanticContracts.TeamCityResultHidden)

  @Test def testReporting_ConfiguredResultLoggerCanBeRestored(): Unit = run(
    "junit-configured-result-task-output", "testSupport/JUnit_PassAndFailure",
    behavior = Seq("test"), success = false,
    options = Seq("-Dteamcity.sbt.logger.useTeamCityTestResultLogger=false"),
    verification = SbtJUnitResultLoggerSemanticContracts.ConfiguredResultWithTaskOutput)

  @Test def testReporting_ConfiguredResultLoggerRemainsVisibleWithoutTestTaskOutput(): Unit = run(
    "junit-configured-result-no-task-output", "testSupport/JUnit_PassAndFailure",
    behavior = Seq("test"), success = false,
    options = Seq(
      "-Dteamcity.sbt.logger.useTeamCityTestResultLogger=false",
      "-Dteamcity.sbt.logger.showTestTaskOutput=false"
    ),
    verification = SbtJUnitResultLoggerSemanticContracts.ConfiguredResultHidden)

  @Test def testReporting_CustomResultLoggerKeepsItsFailureSemantics(): Unit = run(
    "custom-result-logger-no-task-output", "testSupport/JUnit_CustomResultLogger",
    behavior = Seq("test"), success = true,
    options = Seq(
      "-Dteamcity.sbt.logger.useTeamCityTestResultLogger=false",
      "-Dteamcity.sbt.logger.showTestTaskOutput=false"
    ),
    verification = SbtJUnitResultLoggerSemanticContracts.CustomConfiguredResultHidden)

  @Test def compilation_WarningsReportedAsInspections(): Unit = run(
    "compilation-warnings", "compilation/warnings",
    setup = Seq("clean"), behavior = Seq("compile"), success = true, options = Seq.empty,
    verification = SbtOrdinaryCompilationSemanticContracts.Warnings)

  @Test def compilerLogLevel_DebugOutputSuppressedAtError(): Unit = run(
    "compiler-log-level-error", "compilerLogLevel/error",
    behavior = Seq("compile"), success = true,
    verification = SbtCompilationOutputSemanticContracts.CompilerLogLevelError)

  @Test def compilerLogLevel_DebugOutputShownAtDebug(): Unit = run(
    "compiler-log-level-debug", "compilerLogLevel/debug",
    behavior = Seq("compile"), success = true,
    verification = SbtCompilationOutputSemanticContracts.CompilerLogLevelDebug)

  @Test def compilation_SubprojectLifecycleReported(): Unit = run(
    "compilation-subproject", "compilation/subproject",
    behavior = Seq("backend/compile"), success = true, options = Seq("--info"),
    verification = SbtOrdinaryCompilationSemanticContracts.Subproject)

  @Test def testReporting_ScalaTest_PassAndFailureReported(): Unit = run(
    "scalatest-pass-and-failure", "testSupport/ScalaTest_PassAndFailure",
    behavior = Seq("test"), success = false,
    verification = SbtClassicScalaTestSemanticContracts.PassAndFailure)

  @Test def projectExecution_JavaSourcesCompileAndRun(): Unit = run(
    "java-sources-compile-run", "projectExecution/javaSources",
    behavior = Seq("compile", "run"), success = true, options = Seq("--debug"),
    verification = SbtJavaSourcesExecutionSemanticContracts.CompileAndRun)

  @Test def testReporting_Specs2_IgnoredTestsReported(): Unit = run(
    "specs2-ignored-tests", "testSupport/Specs2_IgnoredTests",
    behavior = Seq("test"), success = true, options = Seq("--info"),
    verification = SbtSpecs2SemanticContracts.IgnoredTests)

  @Test def testReporting_ScalaTest_NestedSuitesReported(): Unit = run(
    "scalatest-nested-suites", "testSupport/ScalaTest_NestedSuites",
    behavior = Seq("test"), success = true, options = Seq("--info"),
    verification = SbtClassicScalaTestSemanticContracts.NestedSuites)

  @Test def testReporting_ScalaTest_LongNamesNotDuplicated(): Unit = run(
    "scalatest-long-names", "testSupport/ScalaTest_LongNamesNotDuplicated",
    behavior = Seq("testOnly"), success = true,
    verification = SbtClassicScalaTestSemanticContracts.LongNames)

  @Test def testReporting_Specs2_TestOnlyExamplesReported(): Unit = run(
    "specs2-test-only", "testSupport/Specs2_TestOnlyExamples",
    behavior = Seq("testOnly"), success = true,
    verification = SbtSpecs2SemanticContracts.TestOnlyExamples)

  @Test def testReporting_ScalaTest_ParallelEventsReported(): Unit = run(
    "scalatest-parallel-events", "testSupport/ScalaTest_ParallelEvents",
    behavior = Seq("test"), success = false,
    options = Seq("--info", "-Dteamcity.sbt.logger.showTestTaskOutput=false"),
    verification = SbtScalaTestParallelEventsSemanticContracts.Failure)

  private val ExactCanary = SbtOutputVerificationSelection.ExactByDefault

  private def run(
    scenarioId: String,
    fixture: String,
    setup: Seq[String] = Seq.empty,
    behavior: Seq[String],
    success: Boolean,
    options: Seq[String] = Seq("--error"),
    teamCity: Boolean = true,
    verification: SbtOutputVerificationSelection = SbtOutputVerificationSelection.ExactByDefault
  ): Unit = runCase(SbtLoggerOutputTestCase(
    scenarioId = scenarioId,
    fixture = fixture,
    setupCommands = setup,
    behaviorCommands = behavior,
    expectedResult = if (success) SbtProcessResultExpectation.Success else SbtProcessResultExpectation.Failure,
    sbtOptions = options,
    teamCityEnvironment = teamCity,
    verification = verification
  ))
}
