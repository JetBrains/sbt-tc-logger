package org.jetbrains.sbt.integrationTests

import org.jetbrains.sbt.integrationTests.FileUtils.normalisedAbsolutePath

import java.io.File

/**
 * Describes an sbt plugin artifact that nested sbt integration tests can load with `apply -cp`.
 */
final case class SbtPluginUnderTest(
  jarName: String,
  entrypointClass: String,
  packageCommandHint: String
) {
  def packagedJar(root: File, scalaBinaryVersion: String, sbtBinaryVersion: String): File = {
    val jarRelativePath = s"target/scala-$scalaBinaryVersion/sbt-$sbtBinaryVersion/$jarName"
    val jarFile = new File(root, jarRelativePath).getAbsoluteFile

    if (!jarFile.isFile) {
      throw new IllegalStateException(
        s"""Integration tests load the sbt plugin directly from the assembled plugin jar, but it does not exist: ${jarFile.getAbsolutePath}.
           |Run: $packageCommandHint""".stripMargin
      )
    }

    jarFile
  }

  def loadCommand(pluginJar: File): String =
    s"apply -cp ${normalisedAbsolutePath(pluginJar)} $entrypointClass"
}
