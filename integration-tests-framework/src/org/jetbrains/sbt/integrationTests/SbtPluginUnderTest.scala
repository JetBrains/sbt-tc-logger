package org.jetbrains.sbt.integrationTests

import org.jetbrains.sbt.integrationTests.FileUtils.normalisedAbsolutePath

import java.io.File

/**
 * Describes an sbt plugin artifact that nested sbt integration tests can load with `apply -cp`.
 */
final case class SbtPluginUnderTest(
  entrypointClass: String,
  packageCommandHint: String
) {
  def packagedJar(root: File, sbtBinaryVersion: String): File = {
    // Must match `prepareIntegrationTestArtifacts`: one staged JAR per sbt plugin binary version,
    // independent of `packageBin`'s versioned output layout and filename.
    val jarFile = new File(root, s"target/integration-tests/artifacts/sbt-$sbtBinaryVersion.jar").getAbsoluteFile

    if (!jarFile.isFile) {
      throw new IllegalStateException(
        s"""Integration tests load the sbt plugin directly from the staged logger JAR, but it does not exist: ${jarFile.getAbsolutePath}.
           |Run: $packageCommandHint""".stripMargin
      )
    }

    jarFile
  }

  def loadCommand(pluginJar: File): String =
    s"apply -cp ${normalisedAbsolutePath(pluginJar)} $entrypointClass"
}
