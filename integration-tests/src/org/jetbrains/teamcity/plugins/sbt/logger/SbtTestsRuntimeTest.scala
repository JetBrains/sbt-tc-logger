package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.sbt.integrationTests.SbtIntegrationTestLayout
import org.junit.{Assert, Test}

import java.io.File

class SbtTestsRuntimeTest {

  @Test
  def forSbtVersionDerivesExplicitSbt14AndJdkMetadata(): Unit = {
    assertRuntime(
      SbtTestsRuntime.forSbtVersion("1.4.5", SbtTestJdk.Jdk8, "integration-tests/testData/1.4+", "test-profile"),
      id = "1.4.5-jdk8",
      outputProfile = "test-profile",
      sbtVersion = "1.4.5",
      jdk = SbtTestJdk.Jdk8,
      testDataRelativePath = "integration-tests/testData/1.4+",
      sbtBinaryVersion = "1.0",
      launcherVersion = "1.12.15"
    )
  }

  @Test
  def forSbtVersionDerivesExplicitSbt2AndJdkMetadata(): Unit = {
    assertRuntime(
      SbtTestsRuntime.forSbtVersion("2.0.6", SbtTestJdk.Jdk17, "integration-tests/testData/2.0+", "test-profile"),
      id = "2.0.6-jdk17",
      outputProfile = "test-profile",
      sbtVersion = "2.0.6",
      jdk = SbtTestJdk.Jdk17,
      testDataRelativePath = "integration-tests/testData/2.0+",
      sbtBinaryVersion = "2",
      launcherVersion = "2.0.6"
    )
  }

  @Test
  def forSbtVersionAcceptsPrereleaseVersions(): Unit = {
    Assert.assertEquals("1.12.15", SbtTestsRuntime.forSbtVersion("1.13.0-RC1", SbtTestJdk.Jdk17, "integration-tests/testData/1.4+", "test-profile").launcherVersion)
    Assert.assertEquals("2.0.6", SbtTestsRuntime.forSbtVersion("2.1.0-M2", SbtTestJdk.Jdk17, "integration-tests/testData/2.0+", "test-profile").launcherVersion)
  }

  @Test
  def catalogCoversTheSelectedRuntimeMatrix(): Unit = {
    Assert.assertEquals(
      Seq(
        ("1.4.5-jdk8", "1.4.5", SbtTestJdk.Jdk8, "integration-tests/testData/1.4+"),
        ("1.12.15-jdk8", "1.12.15", SbtTestJdk.Jdk8, "integration-tests/testData/1.4+"),
        ("1.12.15-jdk17", "1.12.15", SbtTestJdk.Jdk17, "integration-tests/testData/1.4+"),
        ("2.0.6-jdk17", "2.0.6", SbtTestJdk.Jdk17, "integration-tests/testData/2.0+")
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
        SbtIntegrationTestLayout.sbtBootDirectory(root, runtime.id),
        SbtIntegrationTestLayout.sbtCoursierHome(root, runtime.id),
        SbtIntegrationTestLayout.sbtIvyHome(root, runtime.id),
        SbtIntegrationTestLayout.sbtGlobalBase(root, runtime.id, runtime.launcherVersion),
        SbtIntegrationTestLayout.sbtGlobalServerDirectory(runtime.id, runtime.launcherVersion)
      ).map(_.getPath)
    }

    Assert.assertEquals(stateDirectories.size, stateDirectories.distinct.size)
  }

  @Test
  def integrationTestCacheDirectoriesCanBeRelocatedOutsideTarget(): Unit = {
    val bootProperty = SbtIntegrationTestLayout.IntegrationTestBootDirectoryProperty
    val coursierProperty = SbtIntegrationTestLayout.IntegrationTestCoursierHomeProperty
    val ivyProperty = SbtIntegrationTestLayout.IntegrationTestIvyHomeProperty
    val previousBootValue = Option(System.getProperty(bootProperty))
    val previousCoursierValue = Option(System.getProperty(coursierProperty))
    val previousIvyValue = Option(System.getProperty(ivyProperty))
    val bootCacheRoot = new File("teamcity-caches/integration-test-sbt-boot")
    val coursierCacheRoot = new File("teamcity-caches/integration-test-coursier")
    val ivyCacheRoot = new File("teamcity-caches/integration-test-ivy")

    try {
      System.setProperty(bootProperty, bootCacheRoot.getPath)
      System.setProperty(coursierProperty, coursierCacheRoot.getPath)
      System.setProperty(ivyProperty, ivyCacheRoot.getPath)
      Assert.assertEquals(
        new File(bootCacheRoot, "1.12.15-jdk17").getAbsolutePath,
        SbtIntegrationTestLayout.sbtBootDirectory(new File("repository-root"), "1.12.15-jdk17").getPath
      )
      Assert.assertEquals(
        new File(coursierCacheRoot, "1.12.15-jdk17").getAbsolutePath,
        SbtIntegrationTestLayout.sbtCoursierHome(new File("repository-root"), "1.12.15-jdk17").getPath
      )
      Assert.assertEquals(
        new File(ivyCacheRoot, "1.12.15-jdk17").getAbsolutePath,
        SbtIntegrationTestLayout.sbtIvyHome(new File("repository-root"), "1.12.15-jdk17").getPath
      )
    } finally {
      previousBootValue match {
        case Some(value) => System.setProperty(bootProperty, value)
        case None => System.clearProperty(bootProperty)
      }
      previousCoursierValue match {
        case Some(value) => System.setProperty(coursierProperty, value)
        case None => System.clearProperty(coursierProperty)
      }
      previousIvyValue match {
        case Some(value) => System.setProperty(ivyProperty, value)
        case None => System.clearProperty(ivyProperty)
      }
    }
  }

  @Test
  def forSbtVersionRejectsMalformedAndUnsupportedVersions(): Unit = {
    Seq("", "not-a-version", "0.13.0", "3.0.0").foreach { version =>
      val error = expectIllegalArgumentException {
        SbtTestsRuntime.forSbtVersion(version, SbtTestJdk.Jdk17, "integration-tests/testData/2.0+", "test-profile")
      }
      Assert.assertTrue(error.getMessage.contains("supported lines are 1.x and 2.x"))
    }
  }

  private def assertRuntime(
    runtime: SbtTestsRuntime,
    id: String,
    outputProfile: String,
    sbtVersion: String,
    jdk: SbtTestJdk,
    testDataRelativePath: String,
    sbtBinaryVersion: String,
    launcherVersion: String
  ): Unit = {
    Assert.assertEquals(id, runtime.id)
    Assert.assertEquals(outputProfile, runtime.outputProfile)
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
