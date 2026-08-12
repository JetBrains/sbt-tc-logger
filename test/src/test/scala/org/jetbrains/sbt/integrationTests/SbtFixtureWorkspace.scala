package org.jetbrains.sbt.integrationTests

import org.jetbrains.sbt.integrationTests.FileUtils.normalisePathSeparator

import java.io.File
import java.nio.file.Files

/**
 * Prepares per-test fixture workspaces for nested sbt runs.
 *
 * Source fixtures stay immutable under `test/testdata`; each run copies one fixture into `target/integration-tests/work`
 * and skips any fixture-local `target` directories. Test-data directories represent SBT plugin binary-version lines;
 * the source `project/build.properties` file contains [[SbtVersionTemplate]] and is rendered only in the copied fixture
 * with the exact nested SBT version selected by the concrete runtime.
 */
object SbtFixtureWorkspace {
  /**
   * Required value of every test fixture's `sbt.version` property.
   *
   * `@SBT_VERSION@` is intentionally not shell or SBT syntax: the harness performs the only substitution after copying
   * the source fixture. This keeps one reusable fixture corpus for each SBT plugin binary-version line.
   */
  val SbtVersionTemplate = "@SBT_VERSION@"

  def sourceFixtureDirectory(root: File, testDataRelativePath: String, testRepo: String): File = {
    val source = new File(new File(root, testDataRelativePath), testRepo).getAbsoluteFile
    if (!source.isDirectory) {
      throw new IllegalStateException("Fixture directory does not exist: " + source.getAbsolutePath)
    }
    source
  }

  def copyFixtureToWorkDirectory(root: File, runtimeId: String, testRepo: String, source: File, sbtVersion: String): File = {
    val targetBase = SbtIntegrationTestLayout.fixtureWorkBase(root, runtimeId)
    val target = normalizedPathParts(testRepo).foldLeft(targetBase) { case (directory, part) => new File(directory, part) }.getAbsoluteFile

    FileUtils.deleteRecursively(target.toPath)
    FileUtils.copyDirectorySkipping(source.toPath, target.toPath, skipDirectoryName = "target")
    renderSbtVersionTemplate(target, sbtVersion)

    target
  }

  private def renderSbtVersionTemplate(fixture: File, sbtVersion: String): Unit = {
    val propertiesFile = new File(fixture, "project/build.properties")
    if (!propertiesFile.isFile) {
      templateError(propertiesFile, "file is missing")
    }

    val content = Files.readString(propertiesFile.toPath)
    val properties = SbtVersionProperty.findAllMatchIn(content).toSeq
    if (properties.size != 1) {
      templateError(propertiesFile, s"expected one sbt.version property but found ${properties.size}")
    }

    val property = properties.head
    if (property.group(1).trim != SbtVersionTemplate) {
      templateError(propertiesFile, s"expected sbt.version=$SbtVersionTemplate")
    }

    val rendered = content.patch(
      property.start(1),
      sbtVersion,
      property.end(1) - property.start(1)
    )
    Files.writeString(propertiesFile.toPath, rendered)
  }

  private def templateError(propertiesFile: File, reason: String): Nothing =
    throw new IllegalStateException(
      s"Invalid SBT version template at ${propertiesFile.getAbsolutePath}: $reason. " +
        s"Expected exactly one sbt.version=$SbtVersionTemplate property."
    )

  private val SbtVersionProperty = """(?m)^[\t ]*sbt\.version[\t ]*=[\t ]*([^\r\n]*)$""".r

  private def normalizedPathParts(path: String): Seq[String] =
    normalisePathSeparator(path).split('/').toSeq.filter(_.nonEmpty)
}
