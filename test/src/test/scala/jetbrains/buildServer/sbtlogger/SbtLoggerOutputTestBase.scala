package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.{IntegrationTestLayout, SbtLoggerOutputTestCase, SbtLoggerPlugin, SbtOutputVerifier, TeamCityOutputNormaliser}
import org.jetbrains.sbt.integrationTests.*
import org.junit.Assert.assertEquals

import java.io.File

/**
 * Base runner for sbt TeamCity logger output integration tests.
 *
 * @param runtime sbt runtime used by every case in this suite instance.
 */
abstract class SbtLoggerOutputTestBase(runtime: SbtTestsRuntime) {

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
   * intentionally compile or test a broken project. For cases that should complete successfully, `expectZeroExitCode`
   * enables the final exit-code assertion here.
   */
  private[sbtlogger] final def runCase(testCase: SbtLoggerOutputTestCase): Unit = {
    val exitCode = runSbtAndTest(
      runtime = runtime,
      sbtOptions = testCase.sbtOptions,
      sbtCommands = testCase.sbtCommands,
      testRepo = testCase.fixture,
      outputFiles = testCase.outputFiles
    )

    if (testCase.expectZeroExitCode) {
      assertEquals(0, exitCode)
    }
  }

  private def runSbtAndTest(
    runtime: SbtTestsRuntime,
    sbtOptions: Seq[String],
    sbtCommands: Seq[String],
    testRepo: String,
    outputFiles: Seq[String]
  ): Int = {
    val root = IntegrationTestLayout.repoRoot()
    val sourceWorkingDir = SbtFixtureWorkspace.sourceFixtureDirectory(root, runtime.testDataRelativePath, testRepo)
    val workingDir = SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, runtime.id, testRepo, sourceWorkingDir)
    val plugin = SbtLoggerPlugin.UnderTest
    val pluginJar = plugin.packagedJar(root, runtime.scalaBinaryVersion, runtime.sbtBinaryVersion)
    val sbtGlobalBase = SbtIntegrationTestLayout.sbtGlobalBase(root, runtime.id, runtime.launcherVersion)
    val sbtVersion = SbtBuildPropertiesUtils.sbtVersionIn(workingDir).getOrElse(Version(runtime.defaultSbtVersion))
    val javaHome = CurrentEnvironment.javaHomeFor(sbtVersion)
    val javaBin = CurrentEnvironment.javaExecutableFor(sbtVersion)

    val commandLinePrefix = Seq(
      javaBin,
      "-Xmx512m",
      "-XX:MaxPermSize=256m",
      "-jar",
      SbtLauncher.sbtLauncher(root, runtime.launcherVersion).getAbsolutePath,
      s"-Dsbt.global.base=${sbtGlobalBase.getAbsolutePath}",
      s"-Dsbt.ivy.home=${SbtIntegrationTestLayout.sbtIvyHome(root).getAbsolutePath}",
      "-Dsbt.log.noformat=true"
    )

    val excludes = new File(sourceWorkingDir, "excludes.txt")
    val excludesFile = Option.when(excludes.exists())(excludes)

    val effectiveOutputFiles =
      if (outputFiles.isEmpty) Seq("output.txt")
      else outputFiles

    val requiredFiles = effectiveOutputFiles.map { outputFile =>
      new File(sourceWorkingDir, outputFile)
    }

    val runResult = SbtProcessRunner.runSbtProcess(
      projectDir = workingDir,
      commandLinePrefix = commandLinePrefix,
      sbtCommands = sbtOptions ++ (plugin.loadCommand(pluginJar) +: sbtCommands) :+ "exit",
      envVars = environmentVariables(sbtGlobalBase, javaHome),
      verbose = true,
      errorsExpected = true,
      diagnosticLineNormaliser = TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput
    )

    SbtOutputVerifier.checkOutputText(runResult.processOutput, excludesFile, requiredFiles)

    runResult.exitCode
  }

  private def environmentVariables(sbtHome: File, javaHome: File): Seq[String] = {
    val javaHomePath = javaHome.getAbsolutePath
    val sbtHomePath = sbtHome.getAbsolutePath

    val path = System.getenv("PATH")
    val pathWithJava = prependPath(s"$javaHomePath/bin", path)

    val teamcityVersion = "9.0.TEST"
    Seq(
      s"PATH=$pathWithJava",
      s"JAVA_HOME=$javaHomePath",
      s"SBT_HOME=$sbtHomePath",
      s"TEAMCITY_VERSION=$teamcityVersion",
    )
  }

  private def prependPath(newPathEntry: String, pathOld: String): String = {
    if (pathOld != null && pathOld.nonEmpty)
      s"$newPathEntry${File.pathSeparator}$pathOld"
    else
      newPathEntry
  }
}
