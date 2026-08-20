package org.jetbrains.sbt.integrationTests

import org.junit.{Assert, Test}

import java.io.File
import java.nio.file.Files

class SbtFixtureWorkspaceTest {

  @Test
  def sbtVersionValuesIgnoreContentWithoutAnSbtVersionDeclaration(): Unit =
    Assert.assertEquals(
      "Expected content without sbt.version to produce no declarations",
      Seq.empty,
      SbtFixtureWorkspace.sbtVersionValues("scala.version=2.13.16\n")
    )

  @Test
  def sbtVersionValuesFindATemplateDeclaration(): Unit =
    Assert.assertEquals(
      "Expected one SBT version template declaration",
      Seq(SbtFixtureWorkspace.SbtVersionTemplate),
      SbtFixtureWorkspace.sbtVersionValues(s"sbt.version=${SbtFixtureWorkspace.SbtVersionTemplate}\n")
    )

  @Test
  def sbtVersionValuesTrimWhitespaceAroundADeclaration(): Unit =
    Assert.assertEquals(
      "Expected whitespace around sbt.version to be ignored",
      Seq(SbtFixtureWorkspace.SbtVersionTemplate),
      SbtFixtureWorkspace.sbtVersionValues(s"  sbt.version  =  ${SbtFixtureWorkspace.SbtVersionTemplate}  \n")
    )

  @Test
  def sbtVersionValuesPreserveDuplicateDeclarations(): Unit =
    Assert.assertEquals(
      "Expected every sbt.version declaration to be reported so duplicate templates can be rejected",
      Seq(SbtFixtureWorkspace.SbtVersionTemplate, SbtFixtureWorkspace.SbtVersionTemplate),
      SbtFixtureWorkspace.sbtVersionValues(
        s"sbt.version=${SbtFixtureWorkspace.SbtVersionTemplate}\nsbt.version=${SbtFixtureWorkspace.SbtVersionTemplate}\n"
      )
    )

  @Test
  def copiedFixtureRendersTheSelectedSbtVersionWithoutChangingTheSource(): Unit =
    withTemporaryDirectory { root =>
      val source = fixture(root, "sbt.version=@SBT_VERSION@\n")

      val copied = SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, "1.12.15", "fixture", source, "1.12.15")

      Assert.assertEquals(
        "Expected the copied fixture to render the selected SBT version",
        "sbt.version=1.12.15\n",
        readBuildProperties(copied)
      )
      Assert.assertEquals(
        "Expected the source fixture to retain the SBT version template",
        "sbt.version=@SBT_VERSION@\n",
        readBuildProperties(source)
      )
    }

  @Test
  def sbt2LocalCacheSettingsAreGeneratedInsideTheCopiedFixture(): Unit =
    withTemporaryDirectory { root =>
      val source = fixture(root, "sbt.version=@SBT_VERSION@\n")
      val copied = SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, "2.0.6-jdk17", "fixture", source, "2.0.6")

      val settingsFile = SbtFixtureWorkspace.writeSbt2LocalCacheSettings(copied)
      val content = Files.readString(settingsFile.toPath)
      val expectedCachePath = FileUtils.normalisedAbsolutePath(new File(copied, ".sbt-tc-logger-cache"))

      Assert.assertEquals(
        "Expected the generated cache setting to stay inside the copied fixture",
        new File(copied, SbtFixtureWorkspace.Sbt2LocalCacheSettingsFileName).getAbsoluteFile,
        settingsFile
      )
      Assert.assertTrue(
        "Expected the generated file to explain SBT 2 cross-workspace caching",
        content.contains("SBT 2 caches task results across workspaces")
      )
      Assert.assertTrue(
        "Expected the generated file to explain its per-run lifecycle",
        content.contains("the harness recreates it for each run")
      )
      Assert.assertTrue(
        "Expected the generated file to configure the scenario-local cache",
        content.contains(s"Global / localCacheDirectory := file(\"$expectedCachePath\")")
      )
      Assert.assertFalse(
        "Expected the immutable source fixture to remain untouched",
        new File(source, SbtFixtureWorkspace.Sbt2LocalCacheSettingsFileName).exists()
      )
    }

  @Test
  def copiedFixtureRejectsAMissingBuildPropertiesFile(): Unit =
    withTemporaryDirectory { root =>
      val source = new File(root, "source")
      Assert.assertTrue(s"Expected to create temporary fixture source directory at $source", source.mkdirs())

      val error = expectIllegalStateException {
        SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, "1.0.4", "fixture", source, "1.0.4")
      }

      assertExceptionMessageContains(error, "file is missing")
    }

  @Test
  def copiedFixtureRejectsAConcreteSbtVersion(): Unit =
    withTemporaryDirectory { root =>
      val source = fixture(root, "sbt.version=1.0.4\n")

      val error = expectIllegalStateException {
        SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, "1.0.4", "fixture", source, "1.0.4")
      }

      assertExceptionMessageContains(error, "expected sbt.version=@SBT_VERSION@")
    }

  @Test
  def copiedFixtureRejectsDuplicateSbtVersionProperties(): Unit =
    withTemporaryDirectory { root =>
      val source = fixture(root, "sbt.version=@SBT_VERSION@\nsbt.version=@SBT_VERSION@\n")

      val error = expectIllegalStateException {
        SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, "1.0.4", "fixture", source, "1.0.4")
      }

      assertExceptionMessageContains(error, "found 2")
    }

  private def fixture(root: File, buildProperties: String): File = {
    val source = new File(root, "source")
    val project = new File(source, "project")
    Assert.assertTrue(s"Expected to create temporary fixture project directory at $project", project.mkdirs())
    Files.writeString(new File(project, "build.properties").toPath, buildProperties)
    source
  }

  private def readBuildProperties(fixture: File): String =
    Files.readString(new File(fixture, "project/build.properties").toPath)

  private def withTemporaryDirectory(action: File => Unit): Unit = {
    val root = Files.createTempDirectory("sbt-fixture-workspace-test-").toFile
    try action(root)
    finally FileUtils.deleteRecursively(root.toPath)
  }

  private def expectIllegalStateException(action: => Unit): IllegalStateException = {
    try {
      action
    } catch {
      case error: IllegalStateException => return error
    }

    Assert.fail("Expected fixture preparation to throw IllegalStateException, but it completed successfully")
    throw new AssertionError("unreachable")
  }

  private def assertExceptionMessageContains(error: IllegalStateException, expectedText: String): Unit = {
    val actualMessage = Option(error.getMessage).getOrElse("<no exception message>")
    Assert.assertTrue(
      s"Expected fixture-preparation error to contain '$expectedText', but was: $actualMessage",
      actualMessage.contains(expectedText)
    )
  }
}
