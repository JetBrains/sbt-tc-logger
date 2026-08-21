package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.{IntegrationTestLayout, SbtLoggerOutputTestCase, SbtLoggerPlugin, SbtOutputVerifier, SbtProcessResultExpectation, SbtTranscriptBoundary, TeamCityOutputNormaliser, TranscriptContext}
import org.jetbrains.sbt.integrationTests.*
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.experimental.categories.Category

import java.io.File

/** Marks suites that launch a nested SBT runtime and therefore belong to the dedicated compatibility matrix. */
trait SbtRuntimeMatrix

/** Base runner for exact, fixture-backed sbt TeamCity logger transcripts. */
@Category(Array(classOf[SbtRuntimeMatrix]))
abstract class SbtLoggerOutputTestBase(runtime: SbtTestsRuntime) {

  protected final def detailedDependencyResolution_CoursierOutcomesReported(): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = "dependency-detailed-outcomes",
      fixture = "compilation/success",
      setupCommands = Seq("clean"),
      behaviorCommands = Seq("update", "update"),
      expectedResult = SbtProcessResultExpectation.Success,
      sbtOptions = Seq("-Dteamcity.sbt.logger.detailedDependencyResolution=true")
    ))

  protected final def detailedDependencyResolution_CoursierFailureIsWarningAndCloses(): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = "dependency-detailed-failure",
      fixture = "dependencyResolution/updateFailure",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("update"),
      expectedResult = SbtProcessResultExpectation.Failure,
      sbtOptions = Seq("-Dteamcity.sbt.logger.detailedDependencyResolution=true")
    ))

  protected final def detailedDependencyResolution_DebugKeepsNativeLogging(): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = "dependency-detailed-debug-disabled",
      fixture = "compilation/success",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("update"),
      expectedResult = SbtProcessResultExpectation.Success,
      sbtOptions = Seq("--debug", "-Dteamcity.sbt.logger.detailedDependencyResolution=true")
    ))

  protected final def detailedDependencyResolution_PreserveConsoleDisablesDetailedMode(): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = "dependency-detailed-preserve-console-disabled",
      fixture = "compilation/success",
      setupCommands = Seq("clean"),
      behaviorCommands = Seq("update"),
      expectedResult = SbtProcessResultExpectation.Success,
      sbtOptions = Seq(
        "-Dteamcity.sbt.logger.preserveConsole=true",
        "-Dteamcity.sbt.logger.detailedDependencyResolution=true"
      )
    ))

  protected final def runScalaTestErrorLikeOutputNotCompilationFailureCase(): Unit =
    runCase(SbtLoggerOutputTestCase(
      scenarioId = "scalatest-error-like-output",
      fixture = "testSupport/ScalaTest_ErrorLikeOutputNotCompilationFailure",
      setupCommands = Seq.empty,
      behaviorCommands = Seq("test"),
      expectedResult = SbtProcessResultExpectation.Success
    ))

  /**
   * Runs a scenario in a fresh nested SBT JVM. The automatic status command is both a configuration assertion and the
   * transcript boundary: plain SBT startup/setup output before it is ignored, while pre-boundary TeamCity messages fail.
   */
  private[sbtlogger] final def runCase(testCase: SbtLoggerOutputTestCase): SbtProcessRunner.ProcessRunResult = {
    val root = IntegrationTestLayout.repoRoot()
    val fixtureRoot = testCase.fixtureRootRelativePath.getOrElse(runtime.testDataRelativePath)
    val sourceWorkingDir = SbtFixtureWorkspace.sourceFixtureDirectory(root, fixtureRoot, testCase.fixture)

    // Scenario IDs, rather than random UUIDs or fixture paths, keep every machine-specific path stable and reviewable.
    val workingDir = SbtFixtureWorkspace.copyFixtureToWorkDirectory(
      root,
      runtime.id,
      testCase.scenarioId,
      sourceWorkingDir,
      runtime.sbtVersion
    )
    val pluginJar = SbtLoggerPlugin.UnderTest.packagedJar(root, runtime.sbtBinaryVersion)
    val sbtGlobalBase = SbtIntegrationTestLayout.sbtGlobalBase(
      root,
      runtime.id,
      runtime.launcherVersion,
      testCase.scenarioId
    )
    val sbtVersion = Version(runtime.sbtVersion)
    if (sbtVersion >= Version("2.0.0")) {
      SbtFixtureWorkspace.writeSbt2LocalCacheSettings(workingDir)
    }
    val javaHome = CurrentEnvironment.javaHomeFor(runtime.jdk.majorVersion)
    val javaBin = CurrentEnvironment.javaExecutableFor(runtime.jdk.majorVersion)
    val sbtBootDirectory = SbtIntegrationTestLayout.sbtBootDirectory(root, runtime.id)
    val sbtCoursierHome = SbtIntegrationTestLayout.sbtCoursierHome(root, runtime.id)
    val sbtIvyHome = SbtIntegrationTestLayout.sbtIvyHome(root, runtime.id)

    val sbtGlobalServerDirectory = Option.when(sbtVersion >= Version("1.4.0")) {
      SbtIntegrationTestLayout.sbtGlobalServerDirectory(runtime.id, runtime.launcherVersion, testCase.scenarioId)
    }
    // A stale socket must never reconnect a scenario to a server started by a previous test pass.
    sbtGlobalServerDirectory.foreach(directory => FileUtils.deleteRecursively(directory.toPath))

    val scenarioJvmProperties = testCase.sbtOptions.filter(_.startsWith("-D"))
    val launcherOptions = testCase.sbtOptions.filterNot(_.startsWith("-D"))
    val commandLinePrefix = Seq(javaBin, "-Xmx512m") ++ scenarioJvmProperties ++ Seq(
      s"-Dsbt.global.base=${sbtGlobalBase.getAbsolutePath}",
      s"-Dsbt.boot.directory=${sbtBootDirectory.getAbsolutePath}",
      s"-Dsbt.coursier.home=${sbtCoursierHome.getAbsolutePath}",
      s"-Dsbt.ivy.home=${sbtIvyHome.getAbsolutePath}",
      "-Dsbt.log.noformat=true",
      "-jar",
      SbtLauncher.sbtLauncher(root, runtime.launcherVersion).getAbsolutePath
    )

    val effectiveCommands =
      Seq(SbtLoggerPlugin.UnderTest.loadCommand(pluginJar)) ++
        testCase.setupCommands ++
        Seq("sbt-teamcity-logger") ++
        testCase.behaviorCommands

    val environmentVariablesToRemove = Seq("COURSIER_CACHE") ++
      Option.when(!testCase.teamCityEnvironment)("TEAMCITY_VERSION")
    val runResult = SbtProcessRunner.runSbtProcess(
      projectDir = workingDir,
      commandLinePrefix = commandLinePrefix,
      sbtOptions = launcherOptions,
      sbtCommands = effectiveCommands,
      envVars = environmentVariables(sbtGlobalBase, sbtGlobalServerDirectory, javaHome, testCase.teamCityEnvironment),
      verbose = true,
      errorsExpected = true,
      diagnosticLineNormaliser = TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput,
      environmentVariablesToRemove = environmentVariablesToRemove
    )

    val preserveConsole = propertyEnabled(testCase.sbtOptions, "teamcity.sbt.logger.preserveConsole")
    val useTeamCityTestResultLogger = propertyEnabled(
      testCase.sbtOptions,
      "teamcity.sbt.logger.useTeamCityTestResultLogger",
      defaultValue = true
    )
    val showTestTaskOutput = propertyEnabled(
      testCase.sbtOptions,
      "teamcity.sbt.logger.showTestTaskOutput",
      defaultValue = true
    )
    // The status command reports configured values; the transcript separately proves preserve-console suppresses the adapter.
    val detailedDependencies = propertyEnabled(testCase.sbtOptions, "teamcity.sbt.logger.detailedDependencyResolution")
    val renderObjectEventDetails = propertyEnabled(testCase.sbtOptions, "teamcity.sbt.logger.renderObjectEventDetails")
    val bounded = SbtTranscriptBoundary.extract(
      runResult.processOutput,
      SbtTranscriptBoundary.ExpectedHandshake(
        teamCityVersion = Option.when(testCase.teamCityEnvironment)("9.0.TEST"),
        preserveConsole = preserveConsole,
        useTeamCityTestResultLogger = useTeamCityTestResultLogger,
        showTestTaskOutput = showTestTaskOutput,
        detailedDependencyResolution = detailedDependencies,
        renderObjectEventDetails = renderObjectEventDetails
      )
    )
    val context = TranscriptContext(root, workingDir, sbtGlobalBase, sbtIvyHome, javaHome, bounded.loggerVersion)
    val golden = SbtOutputVerifier.goldenFile(sourceWorkingDir, runtime.outputProfile, testCase.scenarioId)
    if (java.lang.Boolean.getBoolean(SbtOutputVerifier.CandidateModeProperty)) {
      val candidate = SbtOutputVerifier.candidateFile(root, runtime.outputProfile, testCase.scenarioId)
      SbtOutputVerifier.writeCandidate(bounded.lines, candidate, context)
      println(s"Exact transcript candidate: ${candidate.getAbsolutePath}")
    } else {
      SbtOutputVerifier.verify(bounded.lines, golden, context)
    }

    testCase.expectedResult match {
      case SbtProcessResultExpectation.Success =>
        assertEquals(s"Scenario '${testCase.scenarioId}' must succeed", 0, runResult.exitCode)
      case SbtProcessResultExpectation.Failure =>
        assertTrue(s"Scenario '${testCase.scenarioId}' must fail, but exited with 0", runResult.exitCode != 0)
    }
    runResult
  }

  private def propertyEnabled(options: Seq[String], name: String, defaultValue: Boolean = false): Boolean =
    options.reverseIterator.collectFirst {
      case option if option == s"-D$name" => true
      case option if option.startsWith(s"-D$name=") => option.substring(option.indexOf('=') + 1).toBoolean
    }.getOrElse(defaultValue)

  private def environmentVariables(
    sbtHome: File,
    sbtGlobalServerDirectory: Option[File],
    javaHome: File,
    includeTeamcityVersion: Boolean
  ): Seq[String] = {
    val javaHomePath = javaHome.getAbsolutePath
    val pathWithJava = prependPath(s"$javaHomePath/bin", System.getenv("PATH"))
    Seq(
      s"PATH=$pathWithJava",
      s"JAVA_HOME=$javaHomePath",
      s"SBT_HOME=${sbtHome.getAbsolutePath}"
    ) ++ sbtGlobalServerDirectory.map(dir => s"SBT_GLOBAL_SERVER_DIR=${dir.getAbsolutePath}") ++
      Option.when(includeTeamcityVersion)("TEAMCITY_VERSION=9.0.TEST")
  }

  private def prependPath(newPathEntry: String, oldPath: String): String =
    if (oldPath != null && oldPath.nonEmpty) s"$newPathEntry${File.pathSeparator}$oldPath" else newPathEntry
}
