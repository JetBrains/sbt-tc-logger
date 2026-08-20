package org.jetbrains.sbt.integrationTests

import org.jetbrains.sbt.integrationTests.FileUtils.normalisePathSeparator

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Prepares per-test fixture workspaces for nested sbt runs.
 *
 * Source fixtures stay immutable under `test/testdata`; each run copies one fixture into `target/integration-tests/work`
 * and skips any fixture-local `target` directories. Test-data directory names state a fixture root's minimum supported
 * SBT version; the source `project/build.properties` file contains [[SbtVersionTemplate]] and is rendered only in the
 * copied fixture with the exact nested SBT version selected by the concrete runtime.
 */
object SbtFixtureWorkspace {
  /**
   * Required value of every test fixture's `sbt.version` property.
   *
   * `@SBT_VERSION@` is intentionally not shell or SBT syntax: the harness performs the only substitution after copying
   * the source fixture. This keeps one reusable fixture corpus for each compatible SBT range.
   */
  val SbtVersionTemplate = "@SBT_VERSION@"
  val Sbt2LocalCacheSettingsFileName = "teamcity-integration-cache.sbt"

  /**
   * Returns the values of every line-oriented `sbt.version` declaration in source order.
   *
   * Values are trimmed so callers can validate a source fixture independently of insignificant surrounding whitespace.
   * An empty result means that the content does not declare `sbt.version`.
   */
  def sbtVersionValues(content: String): Seq[String] =
    sbtVersionDeclarations(content).map(_.value)

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

  /**
   * Writes an SBT 2-only setting into a copied fixture before its first project load.
   *
   * SBT 2 automatically caches task results across workspaces. A scenario-local cache ensures compile and test tasks
   * execute instead of reusing another fixture's result, while loading the setting from the build avoids a second
   * settings reapplication caused by issuing `set Global / localCacheDirectory` after startup.
   */
  def writeSbt2LocalCacheSettings(workspace: File): File = {
    val settingsFile = new File(workspace, Sbt2LocalCacheSettingsFileName).getAbsoluteFile
    val cachePath = normalisePathSeparator(new File(workspace, ".sbt-tc-logger-cache").getAbsolutePath)
      .replace("\"", "\\\"")
    val content =
      s"""// Generated in this copied fixture because SBT 2 caches task results across workspaces by default.
         |// The scenario-local cache makes compile and test tasks execute so their logger service messages are covered.
         |// Keep this file for every settings reapplication in this SBT process; the harness recreates it for each run.
         |Global / localCacheDirectory := file("$cachePath")
         |""".stripMargin

    Files.writeString(settingsFile.toPath, content, StandardCharsets.UTF_8)
    settingsFile
  }

  private def renderSbtVersionTemplate(fixture: File, sbtVersion: String): Unit = {
    val propertiesFile = new File(fixture, "project/build.properties")
    if (!propertiesFile.isFile) {
      templateError(propertiesFile, "file is missing")
    }

    val content = Files.readString(propertiesFile.toPath)
    val declarations = sbtVersionDeclarations(content)
    if (declarations.size != 1) {
      templateError(propertiesFile, s"expected one sbt.version property but found ${declarations.size}")
    }

    val declaration = declarations.head
    if (declaration.value != SbtVersionTemplate) {
      templateError(propertiesFile, s"expected sbt.version=$SbtVersionTemplate")
    }

    val rendered = content.patch(
      declaration.valueStart,
      sbtVersion,
      declaration.valueEnd - declaration.valueStart
    )
    Files.writeString(propertiesFile.toPath, rendered)
  }

  private def sbtVersionDeclarations(content: String): Seq[SbtVersionDeclaration] =
    SbtVersionProperty.findAllMatchIn(content).map { property =>
      SbtVersionDeclaration(
        value = property.group(1).trim,
        valueStart = property.start(1),
        valueEnd = property.end(1)
      )
    }.toSeq

  private def templateError(propertiesFile: File, reason: String): Nothing =
    throw new IllegalStateException(
      s"Invalid SBT version template at ${propertiesFile.getAbsolutePath}: $reason. " +
        s"Expected exactly one sbt.version=$SbtVersionTemplate property."
    )

  private val SbtVersionProperty = """(?m)^[\t ]*sbt\.version[\t ]*=[\t ]*([^\r\n]*)$""".r

  private final case class SbtVersionDeclaration(value: String, valueStart: Int, valueEnd: Int)

  private def normalizedPathParts(path: String): Seq[String] =
    normalisePathSeparator(path).split('/').toSeq.filter(_.nonEmpty)
}
