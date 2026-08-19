package org.jetbrains.sbt.integrationTests

import java.io.File

/**
 * Shared filesystem locations used by nested sbt integration-test processes.
 */
object SbtIntegrationTestLayout {
  private val IntegrationIvyHome = "target/integration-test-ivy"
  private val IntegrationTestsTarget = "target/integration-tests"

  def sbtIvyHome(root: File, runtimeId: String): File =
    new File(root, s"$IntegrationIvyHome/$runtimeId").getAbsoluteFile

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
