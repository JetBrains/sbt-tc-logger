package org.jetbrains.sbt.integrationTests

import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * @note copied from reusable parts of sbt-structure `PluginArtifactsUtils`.
 *       Adaptations: package changed; extractor publishing and plugin-cross-version helpers were intentionally omitted.
 */
object SbtBuildPropertiesUtils {

  def sbtVersionIn(directory: File): Option[Version] = {
    val propertiesFile = sbtBuildPropertiesFile(directory)
    if (propertiesFile.exists())
      readPropertyFrom(propertiesFile, "sbt.version").map(Version(_))
    else
      None
  }

  private def sbtBuildPropertiesFile(base: File): File =
    new File(base, "project/build.properties")

  private def readPropertyFrom(file: File, name: String): Option[String] = {
    val input = Files.newInputStream(file.toPath)
    try {
      val properties = new Properties()
      properties.load(input)
      Option(properties.getProperty(name)).map(_.trim).filter(_.nonEmpty)
    }
    finally input.close()
  }

  // 1.10.7 => 1.10
  // 2.0.0-M3 => 2.0
  def sbtVersionBinary(sbtVersionFull: String): String = {
    sbtVersionFull.split('.') match {
      case Array(a, b) => s"$a.$b"
      case Array(a, b, _*) => s"$a.$b"
      case _ => sbtVersionFull
    }
  }
}
