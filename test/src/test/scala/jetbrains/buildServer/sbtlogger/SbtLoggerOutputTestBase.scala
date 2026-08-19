package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.{AssertionGroup, ExpectationSet, FlowScope, IntegrationTestLayout, SbtCompilationLifecycleExpectation, SbtDependencyLifecycleExpectation, SbtExitCodeExpectation, SbtFailurePropagationExpectation, SbtLoggerOutputTestCase, SbtLoggerPlugin, SbtOutputVerifier, TeamCityOutputNormaliser}
import org.jetbrains.sbt.integrationTests.*
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import java.io.File

/**
 * Base runner for sbt TeamCity logger output integration tests.
 *
 * @param runtime sbt runtime used by every case in this suite instance.
 */
abstract class SbtLoggerOutputTestBase(runtime: SbtTestsRuntime) {

  protected final def detailedDependencyResolution_CoursierOutcomesReported(): Unit = {
    val result = runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/success",
      sbtCommands = Seq("clean", "update", "update"),
      sbtOptions = Seq("-Dteamcity.sbt.logger.detailedDependencyResolution=true"),
      verifyOutput = false,
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Absent),
      isolateSbtServer = true
    ))
    SbtOutputVerifier.assertDetailedDependencyResolution(result.processOutput)
  }

  protected final def detailedDependencyResolution_CoursierFailureIsWarningAndCloses(): Unit = {
    val result = runCase(SbtLoggerOutputTestCase(
      fixture = "dependencyResolution/updateFailure",
      sbtCommands = Seq("update"),
      sbtOptions = Seq("-Dteamcity.sbt.logger.detailedDependencyResolution=true"),
      verifyOutput = false,
      failurePropagation = SbtFailurePropagationExpectation.ProcessExitNonZero,
      expectedExitCode = SbtExitCodeExpectation.NonZero,
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Absent),
      isolateSbtServer = true
    ))
    SbtOutputVerifier.assertDetailedDependencyFailure(result.processOutput)
  }

  protected final def detailedDependencyResolution_DebugKeepsNativeLogging(): Unit = {
    val result = runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/success",
      sbtCommands = Seq("clean", "update"),
      sbtOptions = Seq("--debug", "-Dteamcity.sbt.logger.detailedDependencyResolution=true"),
      verifyOutput = false,
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Absent),
      isolateSbtServer = true
    ))
    SbtOutputVerifier.assertNoDetailedDependencyResolution(result.processOutput)
  }

  protected final def detailedDependencyResolution_PreserveConsoleDisablesDetailedMode(): Unit = {
    val result = runCase(SbtLoggerOutputTestCase(
      fixture = "compilation/success",
      sbtCommands = Seq("clean", "update"),
      sbtOptions = Seq(
        "-Dteamcity.sbt.logger.preserveConsole=true",
        "-Dteamcity.sbt.logger.detailedDependencyResolution=true"
      ),
      verifyOutput = false,
      compilationLifecycle = Some(SbtCompilationLifecycleExpectation.Absent),
      isolateSbtServer = true
    ))
    SbtOutputVerifier.assertNoDetailedDependencyResolution(result.processOutput)
  }

  /** Runs the TW-35693 fixture after a runtime-qualified suite has opted into it. */
  protected final def runScalaTestErrorLikeOutputNotCompilationFailureCase(): Unit =
    runCase(SbtLoggerOutputTestCase(
      fixture = "testSupport/ScalaTest_ErrorLikeOutputNotCompilationFailure",
      sbtCommands = Seq("test"),
      // SBT 2 may schedule the main and test compilation lifecycles in either order.
      expectations = ExpectationSet(Seq(
        FlowScope("compilation", Seq(
          AssertionGroup("main-compilation", "compilation-output.txt"),
          AssertionGroup("test-compilation", "test-compilation-output.txt")
        )),
        FlowScope("test-events", Seq(AssertionGroup("test-events", "output.txt")))
      )),
      expectedExitCode = SbtExitCodeExpectation.Zero
    ))

  /**
   * Runs one output fixture test case.
   *
   * The test approach is fixture-driven described by [[SbtLoggerOutputTestCase]].
   *
   * This method prepares an isolated copy of the fixture project, loads the logger plugin jar for the selected runtime,
   * starts a nested sbt
   * process with TeamCity-like environment variables, and captures its standard output. The captured output is then
   * matched against regex patterns stored in the fixture's expected output files. If the fixture provides `excludes.txt`,
   * those regex patterns must not appear anywhere in the produced output. Required patterns must appear in order, allowing
   * the tests to validate the TeamCity service messages and important sbt log lines without depending on unrelated
   * machine-specific output.
   *
   * Most legacy fixtures assert output shape even when sbt exits with a non-zero status because the scenario itself may
   * intentionally compile or test a broken project. Each case can explicitly require a zero or non-zero process exit
   * when the command result itself is part of the regression contract.
   */
  private[sbtlogger] final def runCase(testCase: SbtLoggerOutputTestCase): SbtProcessRunner.ProcessRunResult = {
    val runResult = runSbtAndTest(
      runtime = runtime,
      fixtureRootRelativePath = testCase.fixtureRootRelativePath.getOrElse(runtime.testDataRelativePath),
      sbtOptions = testCase.sbtOptions,
      sbtCommands = testCase.sbtCommands,
      failurePropagation = testCase.failurePropagation,
      testRepo = testCase.fixture,
      expectations = testCase.expectations,
      verifyOutput = testCase.verifyOutput,
      compilationLifecycle = testCase.compilationLifecycle,
      dependencyLifecycle = testCase.dependencyLifecycle,
      teamCityEnvironment = testCase.teamCityEnvironment,
      expectNoTeamCityMessages = testCase.expectNoTeamCityMessages,
      isolateSbtServer = testCase.isolateSbtServer
    )

    testCase.expectedExitCode match {
      case SbtExitCodeExpectation.Any =>
      case SbtExitCodeExpectation.Zero => assertEquals(0, runResult.exitCode)
      case SbtExitCodeExpectation.NonZero =>
        assertTrue(s"Expected nested sbt command to fail, but it exited with ${runResult.exitCode}", runResult.exitCode != 0)
    }

    SbtFailurePropagationExpectation.assertObserved(
      testCase.failurePropagation,
      runResult.exitCode,
      runResult.processOutput
    )
    runResult
  }

  private def runSbtAndTest(
    runtime: SbtTestsRuntime,
    fixtureRootRelativePath: String,
    sbtOptions: Seq[String],
    sbtCommands: Seq[String],
    failurePropagation: SbtFailurePropagationExpectation,
    testRepo: String,
    expectations: ExpectationSet,
    verifyOutput: Boolean,
    compilationLifecycle: Option[SbtCompilationLifecycleExpectation],
    dependencyLifecycle: Option[SbtDependencyLifecycleExpectation],
    teamCityEnvironment: Boolean = true,
    expectNoTeamCityMessages: Boolean = false,
    isolateSbtServer: Boolean = false
  ): SbtProcessRunner.ProcessRunResult = {
    val root = IntegrationTestLayout.repoRoot()
    val sourceWorkingDir = SbtFixtureWorkspace.sourceFixtureDirectory(root, fixtureRootRelativePath, testRepo)
    val isolatedSessionId = Option.when(isolateSbtServer)(s"detail-${java.util.UUID.randomUUID()}")
    // SBT 2 task-cache keys include the fixture's work directory. An isolated server must use an isolated
    // work directory too, otherwise a prior run of the same fixture can satisfy `compile` without invoking Zinc.
    val workspaceId = isolatedSessionId.fold(testRepo)(sessionId => s"$testRepo-$sessionId")
    val workingDir = SbtFixtureWorkspace.copyFixtureToWorkDirectory(
      root,
      runtime.id,
      workspaceId,
      sourceWorkingDir,
      runtime.sbtVersion
    )
    val plugin = SbtLoggerPlugin.UnderTest
    val pluginJar = plugin.packagedJar(root, runtime.sbtBinaryVersion)
    val sbtGlobalBase = isolatedSessionId match {
      case Some(sessionId) => SbtIntegrationTestLayout.sbtGlobalBase(root, runtime.id, runtime.launcherVersion, sessionId)
      case None => SbtIntegrationTestLayout.sbtGlobalBase(root, runtime.id, runtime.launcherVersion)
    }
    val sbtVersion = Version(runtime.sbtVersion)
    val javaHome = CurrentEnvironment.javaHomeFor(runtime.jdk)
    val javaBin = CurrentEnvironment.javaExecutableFor(runtime.jdk)

    val commandLinePrefix = Seq(
      javaBin,
      "-Xmx512m",
      "-jar",
      SbtLauncher.sbtLauncher(root, runtime.launcherVersion).getAbsolutePath,
      s"-Dsbt.global.base=${sbtGlobalBase.getAbsolutePath}",
      s"-Dsbt.ivy.home=${SbtIntegrationTestLayout.sbtIvyHome(root, runtime.id).getAbsolutePath}",
      "-Dsbt.log.noformat=true"
    )

    val excludes = new File(sourceWorkingDir, "excludes.txt")
    val excludesFile = Option.when(excludes.exists())(excludes)

    if (verifyOutput) SbtOutputVerifier.validateExpectationSet(expectations, sourceWorkingDir)

    // Recent SBT versions use a Unix-domain socket for their server. Keep it in a short directory to avoid exceeding
    // the platform's socket-path limit when the test harness isolates its global base under the repository.
    val sbtGlobalServerDirectory = Option.when(sbtVersion >= Version("1.4.0")) {
      isolatedSessionId match {
        case Some(sessionId) => SbtIntegrationTestLayout.sbtGlobalServerDirectory(runtime.id, runtime.launcherVersion, sessionId)
        case None => SbtIntegrationTestLayout.sbtGlobalServerDirectory(runtime.id, runtime.launcherVersion)
      }
    }

    // SBT 2 caches task results across fixture workspaces by default. Give each
    // copied fixture its own local cache so its compile/test task is executed and
    // the logger service-message lifecycle is actually covered. This setting was
    // introduced by SBT 2, so legacy SBT 1 runtimes must not receive its slash syntax.
    val localCacheCommand = Option.when(sbtVersion >= Version("2.0.0")) {
      s"set Global / localCacheDirectory := file(\"${new File(workingDir, ".sbt-tc-logger-cache").getAbsolutePath}\")"
    }

    val failurePropagationSetup = SbtFailurePropagationExpectation.setupCommand(failurePropagation)

    val effectiveSbtCommands: Seq[String] =
      plugin.loadCommand(pluginJar) +: (localCacheCommand.toSeq ++ failurePropagationSetup.toSeq ++ sbtCommands :+ "exit")

    val environmentVariablesToRemove =
      if (teamCityEnvironment) Seq.empty
      // ProcessBuilder inherits the TeamCity agent environment, so merely omitting our test value would still leave this set.
      else Seq("TEAMCITY_VERSION")

    val runResult = SbtProcessRunner.runSbtProcess(
      projectDir = workingDir,
      commandLinePrefix = commandLinePrefix,
      sbtOptions = sbtOptions,
      sbtCommands = effectiveSbtCommands,
      envVars = environmentVariables(sbtGlobalBase, sbtGlobalServerDirectory, javaHome, teamCityEnvironment),
      verbose = true,
      errorsExpected = true,
      diagnosticLineNormaliser = TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput,
      environmentVariablesToRemove = environmentVariablesToRemove
    )

    if (verifyOutput) SbtOutputVerifier.checkOutputText(runResult.processOutput, excludesFile, expectations, sourceWorkingDir)
    compilationLifecycle.foreach { expectation =>
      SbtOutputVerifier.assertCompilationLifecycle(runResult.processOutput, expectation)
    }
    dependencyLifecycle.foreach { expectation =>
      SbtOutputVerifier.assertDependencyLifecycle(runResult.processOutput, expectation)
    }
    if (expectNoTeamCityMessages) {
      assertFalse("Logger emitted TeamCity service messages outside TeamCity", runResult.processOutput.contains("##teamcity["))
    }

    runResult
  }

  private def environmentVariables(
    sbtHome: File,
    sbtGlobalServerDirectory: Option[File],
    javaHome: File,
    includeTeamcityVersion: Boolean
  ): Seq[String] = {
    val javaHomePath = javaHome.getAbsolutePath
    val sbtHomePath = sbtHome.getAbsolutePath

    val path = System.getenv("PATH")
    val pathWithJava = prependPath(s"$javaHomePath/bin", path)

    val sbtGlobalServerDirEnv: Option[String] = sbtGlobalServerDirectory.map { dir =>
      s"SBT_GLOBAL_SERVER_DIR=${dir.getAbsolutePath}"
    }
    val teamcityVersionEnv: Option[String] = Option.when(includeTeamcityVersion) {
      "TEAMCITY_VERSION=9.0.TEST"
    }

    Seq(
      s"PATH=$pathWithJava",
      s"JAVA_HOME=$javaHomePath",
      s"SBT_HOME=$sbtHomePath",
    ) ++ sbtGlobalServerDirEnv
      ++ teamcityVersionEnv
  }

  private def prependPath(newPathEntry: String, pathOld: String): String = {
    if (pathOld != null && pathOld.nonEmpty)
      s"$newPathEntry${File.pathSeparator}$pathOld"
    else
      newPathEntry
  }
}
