package org.jetbrains.sbt.integrationTests

import java.io.File

/**
 * Shared filesystem locations used by nested sbt integration-test processes.
 */
object SbtIntegrationTestLayout {
  private val IntegrationIvyHome = "target/integration-test-ivy"
  private val IntegrationTestsTarget = "target/integration-tests"

  def sbtIvyHome(root: File): File =
    new File(root, IntegrationIvyHome).getAbsoluteFile

  def sbtGlobalBase(root: File, runtimeId: String): File =
    new File(root, s"$IntegrationTestsTarget/sbt-global-apply/$runtimeId").getAbsoluteFile

  def sbtGlobalBase(root: File, runtimeId: String, launcherVersion: String): File =
    new File(root, s"$IntegrationTestsTarget/sbt-global-apply/$runtimeId-$launcherVersion").getAbsoluteFile

  def sbtLauncherCache(root: File): File =
    new File(root, s"$IntegrationTestsTarget/sbt-launcher").getAbsoluteFile

  def fixtureWorkBase(root: File, runtimeId: String): File =
    new File(root, s"$IntegrationTestsTarget/work/$runtimeId").getAbsoluteFile
}
