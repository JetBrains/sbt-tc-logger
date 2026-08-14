package jetbrains.buildServer.sbtlogger

import org.jetbrains.sbt.integrationTests.SbtIntegrationTestLayout
import org.junit.{Assert, Test}

import java.io.File

class SbtTestsRuntimeTest {

  @Test
  def forSbtVersionDerivesExplicitSbt1AndJdkMetadata(): Unit = {
    assertRuntime(
      SbtTestsRuntime.forSbtVersion("1.0.0", SbtTestJdk.Jdk8, "test/testdata/1.0"),
      id = "1.0.0-jdk8",
      sbtVersion = "1.0.0",
      jdk = SbtTestJdk.Jdk8,
      testDataRelativePath = "test/testdata/1.0",
      sbtBinaryVersion = "1.0",
      launcherVersion = "1.12.15"
    )
  }

  @Test
  def forSbtVersionDerivesExplicitSbt2AndJdkMetadata(): Unit = {
    assertRuntime(
      SbtTestsRuntime.forSbtVersion("2.0.6", SbtTestJdk.Jdk17, "test/testdata/2.0+"),
      id = "2.0.6-jdk17",
      sbtVersion = "2.0.6",
      jdk = SbtTestJdk.Jdk17,
      testDataRelativePath = "test/testdata/2.0+",
      sbtBinaryVersion = "2",
      launcherVersion = "2.0.6"
    )
  }

  @Test
  def forSbtVersionAcceptsPrereleaseVersions(): Unit = {
    Assert.assertEquals("1.12.15", SbtTestsRuntime.forSbtVersion("1.13.0-RC1", SbtTestJdk.Jdk17, "test/testdata/1.3+").launcherVersion)
    Assert.assertEquals("2.0.6", SbtTestsRuntime.forSbtVersion("2.1.0-M2", SbtTestJdk.Jdk17, "test/testdata/2.0+").launcherVersion)
  }

  @Test
  def catalogCoversTheSelectedRuntimeMatrix(): Unit = {
    Assert.assertEquals(
      Seq(
        ("1.0.0-jdk8", "1.0.0", SbtTestJdk.Jdk8, "test/testdata/1.0"),
        ("1.12.15-jdk8", "1.12.15", SbtTestJdk.Jdk8, "test/testdata/1.3+"),
        ("1.12.15-jdk17", "1.12.15", SbtTestJdk.Jdk17, "test/testdata/1.3+"),
        ("2.0.6-jdk17", "2.0.6", SbtTestJdk.Jdk17, "test/testdata/2.0+")
      ),
      SbtTestsRuntime.All.map(runtime => (runtime.id, runtime.sbtVersion, runtime.jdk, runtime.testDataRelativePath))
    )
    Assert.assertEquals(SbtTestsRuntime.All.size, SbtTestsRuntime.All.map(_.id).distinct.size)
  }

  @Test
  def runtimeIdsIsolateAllNestedSbtStateDirectories(): Unit = {
    val root = new File("repository-root")

    val stateDirectories = SbtTestsRuntime.All.flatMap { runtime =>
      Seq(
        SbtIntegrationTestLayout.fixtureWorkBase(root, runtime.id),
        SbtIntegrationTestLayout.sbtIvyHome(root, runtime.id),
        SbtIntegrationTestLayout.sbtGlobalBase(root, runtime.id, runtime.launcherVersion),
        SbtIntegrationTestLayout.sbtGlobalServerDirectory(runtime.id, runtime.launcherVersion)
      ).map(_.getPath)
    }

    Assert.assertEquals(stateDirectories.size, stateDirectories.distinct.size)
  }

  @Test
  def forSbtVersionRejectsMalformedAndUnsupportedVersions(): Unit = {
    Seq("", "not-a-version", "0.13.0", "3.0.0").foreach { version =>
      val error = expectIllegalArgumentException {
        SbtTestsRuntime.forSbtVersion(version, SbtTestJdk.Jdk17, "test/testdata/2.0+")
      }
      Assert.assertTrue(error.getMessage.contains("supported lines are 1.x and 2.x"))
    }
  }

  private def assertRuntime(
    runtime: SbtTestsRuntime,
    id: String,
    sbtVersion: String,
    jdk: SbtTestJdk,
    testDataRelativePath: String,
    sbtBinaryVersion: String,
    launcherVersion: String
  ): Unit = {
    Assert.assertEquals(id, runtime.id)
    Assert.assertEquals(sbtVersion, runtime.sbtVersion)
    Assert.assertEquals(jdk, runtime.jdk)
    Assert.assertEquals(testDataRelativePath, runtime.testDataRelativePath)
    Assert.assertEquals(sbtBinaryVersion, runtime.sbtBinaryVersion)
    Assert.assertEquals(launcherVersion, runtime.launcherVersion)
  }

  private def expectIllegalArgumentException(block: => Unit): IllegalArgumentException = {
    try {
      block
    } catch {
      case error: IllegalArgumentException => return error
    }

    Assert.fail("Expected IllegalArgumentException")
    throw new AssertionError("unreachable")
  }
}
