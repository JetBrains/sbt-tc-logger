package org.jetbrains.sbt.integrationTests

import java.io.File

/**
 * Shared filesystem locations used by nested sbt integration-test processes.
 */
object SbtIntegrationTestLayout {
  val IntegrationTestBootDirectoryProperty = "sbt.logger.integration-test-boot.directory"
  val IntegrationTestCoursierHomeProperty = "sbt.logger.integration-test-coursier.home"
  val IntegrationTestIvyHomeProperty = "sbt.logger.integration-test-ivy.home"

  private val IntegrationTestBootDirectory = "target/integration-test-sbt-boot"
  private val IntegrationTestCoursierHome = "target/integration-test-coursier"
  private val IntegrationIvyHome = "target/integration-test-ivy"
  private val IntegrationTestsTarget = "target/integration-tests"

  /**
   * Keeps the sbt-launch bootstrap state outside target when TeamCity restores it from Build Cache.
   * Runtime-specific directories avoid concurrent test suites sharing a mutable launcher lock.
   */
  def sbtBootDirectory(root: File, runtimeId: String): File =
    new File(integrationTestRoot(root, IntegrationTestBootDirectoryProperty, IntegrationTestBootDirectory), runtimeId).getAbsoluteFile

  def sbtCoursierHome(root: File, runtimeId: String): File =
    new File(integrationTestRoot(root, IntegrationTestCoursierHomeProperty, IntegrationTestCoursierHome), runtimeId).getAbsoluteFile

  def sbtIvyHome(root: File, runtimeId: String): File =
    new File(integrationTestRoot(root, IntegrationTestIvyHomeProperty, IntegrationIvyHome), runtimeId).getAbsoluteFile

  private def integrationTestRoot(root: File, property: String, defaultRelativePath: String): File =
    sys.props
      .get(property)
      .map(new File(_))
      .getOrElse(new File(root, defaultRelativePath))

  def sbtGlobalBase(root: File, runtimeId: String): File =
    new File(root, s"$IntegrationTestsTarget/sbt-global-apply/$runtimeId").getAbsoluteFile

  def sbtGlobalBase(root: File, runtimeId: String, launcherVersion: String): File =
    new File(root, s"$IntegrationTestsTarget/sbt-global-apply/$runtimeId-$launcherVersion").getAbsoluteFile

  def sbtGlobalBase(root: File, runtimeId: String, launcherVersion: String, sessionId: String): File =
    new File(root, s"$IntegrationTestsTarget/sbt-global-apply/$runtimeId-$launcherVersion-$sessionId").getAbsoluteFile

  /**
   * SBT 2 starts a per-global-base Unix-domain socket server. The regular global
   * base intentionally lives under the repository target directory, but that path
   * can exceed the operating system socket limit. Keep only the server socket in a
   * short, runtime-specific temporary directory.
   */
  def sbtGlobalServerDirectory(runtimeId: String, launcherVersion: String): File = {
    val suffix = s"tc-sbt-${runtimeId.replace('.', '-')}-${launcherVersion.replace('.', '-')}"
    if (File.separatorChar == '/') new File("/tmp", suffix).getAbsoluteFile
    else new File(System.getProperty("java.io.tmpdir"), suffix).getAbsoluteFile
  }

  def sbtGlobalServerDirectory(runtimeId: String, launcherVersion: String, sessionId: String): File = {
    val suffix = s"tc-sbt-${runtimeId.replace('.', '-')}-${launcherVersion.replace('.', '-')}-${sessionId}"
    if (File.separatorChar == '/') new File("/tmp", suffix).getAbsoluteFile
    else new File(System.getProperty("java.io.tmpdir"), suffix).getAbsoluteFile
  }

  def sbtLauncherCache(root: File): File =
    new File(root, s"$IntegrationTestsTarget/sbt-launcher").getAbsoluteFile

  def fixtureWorkBase(root: File, runtimeId: String): File =
    new File(root, s"$IntegrationTestsTarget/work/$runtimeId").getAbsoluteFile
}
