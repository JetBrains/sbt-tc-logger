package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.IntegrationTestLayout
import org.jetbrains.sbt.integrationTests.SbtFixtureWorkspace
import org.junit.{Assert, Test}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Guards source-fixture contracts which are independent of a nested runtime execution. */
class SbtFixtureTemplateContractTest {

  private val commonScenarios = Set(
    "compilation-failure",
    "compilation-concurrent-main-test",
    "compilation-multiproject-failure",
    "compilation-multiproject-failure-debug",
    "compilation-preserve-console",
    "compilation-subproject",
    "compilation-success",
    "compilation-up-to-date",
    "compilation-warnings",
    "compile-incremental",
    "compile-inputs",
    "compile-outside-teamcity",
    "compiler-log-level-debug",
    "compiler-log-level-error",
    "custom-result-logger-no-task-output",
    "dependency-update-default",
    "dependency-update-failure-default",
    "failed-tests-outside-teamcity",
    "java-sources-compile-run",
    "junit-configured-result-no-task-output",
    "junit-configured-result-task-output",
    "junit-pass-and-failure",
    "junit-teamcity-result-no-task-output",
    "junit-test-only",
    "junit-test-quick",
    "logging-custom-manager-preserved",
    "logging-custom-manager-replaced",
    "logging-generic-levels",
    "plugin-status-active",
    "plugin-status-configured",
    "plugin-status-outside-teamcity",
    "project-no-build-file",
    "scalatest-long-names",
    "scalatest-nested-suites",
    "scalatest-parallel-events",
    "scalatest-pass-and-failure",
    "specs2-ignored-tests",
    "specs2-test-only",
    "test-compilation-failure",
    "tests-preserve-console"
  )
  private val sbt1_9PlusScenarios = Set("integration-test-quick", "jacoco")
  private val sbt2PlusScenarios = Set(
    "junit-test-full",
    "junit-test-full-configured-hidden"
  )
  private val jdk11PlusScenarios = Set("scalatest-error-like-output")
  private val modernFrameworkScenarios = Set(
    "modern-junit4",
    "modern-jupiter",
    "modern-munit",
    "modern-scalatest"
  )
  private val testControlMatrixScenarios = Set(
    "custom-result-logger-teamcity-hidden",
    "integration-test-quick-configured-hidden",
    "junit-test-only-configured-hidden",
    "junit-test-quick-configured-hidden"
  )
  private val detailedDependencyScenarios = Set(
    "dependency-detailed-debug-disabled",
    "dependency-detailed-failure",
    "dependency-detailed-outcomes",
    "dependency-detailed-preserve-console-disabled"
  )

  // This independent inventory prevents a scenario from silently losing its golden and catches a golden left behind
  // after a test is removed. Keep it explicit: the source transcript is the sole output contract for each profile.
  private val expectedScenariosByProfile = Map(
    SbtTestsRuntime.Sbt1_4_Jdk8.outputProfile -> commonScenarios,
    SbtTestsRuntime.Sbt1_12_Jdk8.outputProfile -> (commonScenarios ++ sbt1_9PlusScenarios),
    SbtTestsRuntime.Sbt1_12_Jdk17.outputProfile ->
      (commonScenarios ++ sbt1_9PlusScenarios ++ jdk11PlusScenarios ++ modernFrameworkScenarios ++
        testControlMatrixScenarios ++ detailedDependencyScenarios),
    SbtTestsRuntime.Sbt2_0_Jdk17.outputProfile ->
      (commonScenarios ++ sbt1_9PlusScenarios ++ sbt2PlusScenarios ++ jdk11PlusScenarios ++ modernFrameworkScenarios ++
        testControlMatrixScenarios ++ detailedDependencyScenarios)
  )

  @Test def everyDeclaredSbtVersionUsesTheTemplate(): Unit = {
    val root = IntegrationTestLayout.repoRoot().toPath.resolve("test/testdata")
    val declarations = buildPropertiesFiles(root)
      .map(propertiesFile => root.relativize(propertiesFile) -> SbtFixtureWorkspace.sbtVersionValues(Files.readString(propertiesFile)))
      .filter { case (_, values) => values.nonEmpty }
      .sortBy { case (path, _) => path.toString }

    Assert.assertFalse(s"No sbt.version declarations were found under $root", declarations.isEmpty)
    val invalid = declarations.filter { case (_, values) => values != Seq(SbtFixtureWorkspace.SbtVersionTemplate) }
    Assert.assertTrue(
      s"Invalid sbt.version fixture templates:\n${invalid.map { case (path, values) => s"$path: ${values.mkString(", ")}" }.mkString("\n")}",
      invalid.isEmpty
    )
  }

  @Test def everyScenarioProfileHasExactlyOneNonEmptyGoldenAndNoOrphans(): Unit = {
    val root = IntegrationTestLayout.repoRoot().toPath.resolve("test/testdata")
    val expectedFiles = regularFiles(root).filter(path => path.iterator.asScala.exists(_.toString == "expected"))
    val parsed = expectedFiles.map(path => path -> goldenCoordinates(root.relativize(path)))
    val malformed = parsed.collect { case (path, Left(problem)) => s"${root.relativize(path)}: $problem" }
    Assert.assertTrue(s"Malformed exact transcript paths:\n${malformed.mkString("\n")}", malformed.isEmpty)

    val goldens = parsed.collect { case (path, Right(coordinates)) => coordinates -> path }
    val duplicates = goldens.groupBy(_._1).collect {
      case ((profile, scenario), entries) if entries.size != 1 =>
        s"$profile/$scenario: ${entries.map { case (_, path) => root.relativize(path) }.mkString(", ")}"
    }.toSeq.sorted
    Assert.assertTrue(s"Duplicate scenario/profile goldens:\n${duplicates.mkString("\n")}", duplicates.isEmpty)

    val actualByProfile = goldens.groupMap(_._1._1)(_._1._2).view.mapValues(_.toSet).toMap
    val unknownProfiles = actualByProfile.keySet -- expectedScenariosByProfile.keySet
    val differences = expectedScenariosByProfile.toSeq.sortBy(_._1).flatMap { case (profile, expected) =>
      val actual = actualByProfile.getOrElse(profile, Set.empty)
      val missing = (expected -- actual).toSeq.sorted.map(scenario => s"$profile/$scenario: missing")
      val orphaned = (actual -- expected).toSeq.sorted.map(scenario => s"$profile/$scenario: orphaned")
      missing ++ orphaned
    } ++ unknownProfiles.toSeq.sorted.map(profile => s"$profile: unknown output profile")
    Assert.assertTrue(s"Exact transcript inventory mismatch:\n${differences.mkString("\n")}", differences.isEmpty)

    val empty = goldens.collect { case ((profile, scenario), path) if Files.size(path) == 0 => s"$profile/$scenario" }
    Assert.assertTrue(s"Empty goldens must use [[expect-empty]]:\n${empty.mkString("\n")}", empty.isEmpty)
  }

  @Test def noLegacyRegexExpectationsRemain(): Unit = {
    val root = IntegrationTestLayout.repoRoot().toPath.resolve("test/testdata")
    val legacy = regularFiles(root).filter { path =>
      val name = path.getFileName.toString
      name == "excludes.txt" || name.matches("output.*\\.txt")
    }.map(root.relativize).sortBy(_.toString)
    Assert.assertTrue(s"Legacy regex expectation files remain:\n${legacy.mkString("\n")}", legacy.isEmpty)
  }

  private def buildPropertiesFiles(root: Path): Seq[Path] = {
    regularFiles(root).filter(_.getFileName.toString == "build.properties")
  }

  private def regularFiles(root: Path): Seq[Path] = {
    val files = Files.walk(root)
    try files.iterator.asScala.filter(Files.isRegularFile(_)).toSeq
    finally files.close()
  }

  private def goldenCoordinates(relativePath: Path): Either[String, (String, String)] = {
    val segments = relativePath.iterator.asScala.map(_.toString).toVector
    val expectedIndex = segments.lastIndexOf("expected")
    if (expectedIndex < 0 || expectedIndex + 2 != segments.size - 1) {
      Left("expected path must end with expected/<profile>/<scenario-id>.txt")
    } else {
      val fileName = segments.last
      if (!fileName.endsWith(".txt")) Left("golden must be a .txt file")
      else Right(segments(expectedIndex + 1) -> fileName.stripSuffix(".txt"))
    }
  }
}
