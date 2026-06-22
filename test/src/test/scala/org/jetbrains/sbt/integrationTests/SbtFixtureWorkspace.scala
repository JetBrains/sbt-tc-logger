package org.jetbrains.sbt.integrationTests

import org.jetbrains.sbt.integrationTests.FileUtils.normalisePathSeparator

import java.io.File

/**
 * Prepares per-test fixture workspaces for nested sbt runs.
 *
 * Source fixtures stay immutable under `test/testdata`; each run copies one fixture into `target/integration-tests/work`
 * and skips any fixture-local `target` directories.
 */
object SbtFixtureWorkspace {
  def sourceFixtureDirectory(root: File, testDataRelativePath: String, testRepo: String): File = {
    val source = new File(new File(root, testDataRelativePath), testRepo).getAbsoluteFile
    if (!source.isDirectory) {
      throw new IllegalStateException("Fixture directory does not exist: " + source.getAbsolutePath)
    }
    source
  }

  def copyFixtureToWorkDirectory(root: File, runtimeId: String, testRepo: String, source: File): File = {
    val targetBase = SbtIntegrationTestLayout.fixtureWorkBase(root, runtimeId)
    val target = normalizedPathParts(testRepo).foldLeft(targetBase) { case (directory, part) => new File(directory, part) }.getAbsoluteFile

    FileUtils.deleteRecursively(target.toPath)
    FileUtils.copyDirectorySkipping(source.toPath, target.toPath, skipDirectoryName = "target")

    target
  }

  private def normalizedPathParts(path: String): Seq[String] =
    normalisePathSeparator(path).split('/').toSeq.filter(_.nonEmpty)
}
