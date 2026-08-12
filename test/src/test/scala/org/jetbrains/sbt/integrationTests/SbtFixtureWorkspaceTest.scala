package org.jetbrains.sbt.integrationTests

import org.junit.{Assert, Test}

import java.io.File
import java.nio.file.Files

class SbtFixtureWorkspaceTest {

  @Test
  def copiedFixtureRendersTheSelectedSbtVersionWithoutChangingTheSource(): Unit =
    withTemporaryDirectory { root =>
      val source = fixture(root, "sbt.version=@SBT_VERSION@\n")

      val copied = SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, "1.12.15", "fixture", source, "1.12.15")

      Assert.assertEquals("sbt.version=1.12.15\n", readBuildProperties(copied))
      Assert.assertEquals("sbt.version=@SBT_VERSION@\n", readBuildProperties(source))
    }

  @Test
  def copiedFixtureRejectsAMissingBuildPropertiesFile(): Unit =
    withTemporaryDirectory { root =>
      val source = new File(root, "source")
      Assert.assertTrue(source.mkdirs())

      val error = expectIllegalStateException {
        SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, "1.0.0", "fixture", source, "1.0.0")
      }

      Assert.assertTrue(error.getMessage.contains("file is missing"))
    }

  @Test
  def copiedFixtureRejectsAConcreteSbtVersion(): Unit =
    withTemporaryDirectory { root =>
      val source = fixture(root, "sbt.version=1.0.0\n")

      val error = expectIllegalStateException {
        SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, "1.0.0", "fixture", source, "1.0.0")
      }

      Assert.assertTrue(error.getMessage.contains("expected sbt.version=@SBT_VERSION@"))
    }

  @Test
  def copiedFixtureRejectsDuplicateSbtVersionProperties(): Unit =
    withTemporaryDirectory { root =>
      val source = fixture(root, "sbt.version=@SBT_VERSION@\nsbt.version=@SBT_VERSION@\n")

      val error = expectIllegalStateException {
        SbtFixtureWorkspace.copyFixtureToWorkDirectory(root, "1.0.0", "fixture", source, "1.0.0")
      }

      Assert.assertTrue(error.getMessage.contains("found 2"))
    }

  private def fixture(root: File, buildProperties: String): File = {
    val source = new File(root, "source")
    val project = new File(source, "project")
    Assert.assertTrue(project.mkdirs())
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

    Assert.fail("Expected IllegalStateException")
    throw new AssertionError("unreachable")
  }
}
