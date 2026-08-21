package org.jetbrains.sbt.integrationTests

import java.io.File
import scala.io.Source
import scala.util.{Try, Using}

/**
 * @note copied from sbt-structure `org.jetbrains.sbt.integrationTests.utils.CurrentEnvironment`.
 *       Adaptations: package changed; global sbt-structure directories and option building were omitted; Java selection
 *       selects the explicit JDK recorded in the test runtime. This makes the JDK dimension visible in the test
 *       matrix instead of inferring it from an SBT version.
 */
object CurrentEnvironment {

  val OsName: String = System.getProperty("os.name").toLowerCase
  val UserHome: File = new File(System.getProperty("user.home")).getCanonicalFile.ensuring(_.exists())
  val WorkingDir: File = new File(".").getCanonicalFile

  val PossibleJvmLocations: Seq[File] =
    if (OsName.contains("mac")) Seq(
      new File("/Library/Java/JavaVirtualMachines"),
      new File(s"${UserHome.getPath}/Library/Java/JavaVirtualMachines")
    )
    else if (OsName.contains("linux")) Seq(
      new File("/usr/lib/jvm"),
      new File("/usr/java")
    )
    else if (OsName.contains("win")) Seq(
      new File("C:\\Program Files\\Java"),
      new File("C:\\Program Files (x86)\\Java")
    )
    else
      throw new UnsupportedOperationException("Unknown operating system: " + OsName)

  lazy val Java8Home: File = javaHomeForMajor(8)
  lazy val Java8ExecutablePath: String = javaExecutable(Java8Home).getCanonicalPath

  lazy val Java17Home: File = javaHomeForMajor(17)

  lazy val Java17ExecutablePath: String = javaExecutable(Java17Home).getCanonicalPath

  def javaExecutableFor(majorVersion: Int): String = majorVersion match {
    case 8 => Java8ExecutablePath
    case 17 => Java17ExecutablePath
    case unsupported => throw new IllegalArgumentException(s"Unsupported Java major version: $unsupported")
  }

  def javaHomeFor(majorVersion: Int): File = majorVersion match {
    case 8 => Java8Home
    case 17 => Java17Home
    case unsupported => throw new IllegalArgumentException(s"Unsupported Java major version: $unsupported")
  }

  private def javaHomeForMajor(majorVersion: Int): File =
    findJvmInstallation(majorVersion).getOrElse {
      throw new IllegalStateException(
        s"Java $majorVersion was not found in default locations:\n${PossibleJvmLocations.mkString("\n")}. " +
          s"Install an exact Java $majorVersion JDK; this test suite does not fall back to another Java version."
      )
    }

  private def findJvmInstallation(majorVersion: Int): Option[File] = {
    PossibleJvmLocations
      .flatMap { folder =>
        val dirs = Option(folder.listFiles()).getOrElse(Array.empty[File]).filter(_.isDirectory)
        dirs.toSeq
      }
      .map(javaHomeFromInstallation)
      .find(javaHome => javaExecutable(javaHome).isFile && isExactJavaMajor(javaHome, majorVersion))
  }

  private def javaHomeFromInstallation(root: File): File =
    if (OsName.contains("mac")) new File(root, "Contents/Home") else root

  private def isExactJavaMajor(javaHome: File, majorVersion: Int): Boolean =
    Try {
      val process = new ProcessBuilder(javaExecutable(javaHome).getAbsolutePath, "-version")
        .redirectErrorStream(true)
        .start()
      val versionOutput = Using.resource(Source.fromInputStream(process.getInputStream))(_.mkString)
      process.waitFor() == 0 && expectedJavaVersion(majorVersion).findFirstIn(versionOutput).nonEmpty
    }.getOrElse(false)

  private def expectedJavaVersion(majorVersion: Int) = {
    val version = if (majorVersion == 8) "1\\.8(?:\\.|\\\")" else s"$majorVersion(?:\\.|\\\")"
    s"(?m)^(?:openjdk |java )version \\\"$version".r
  }

  private def javaExecutable(javaHome: File): File =
    new File(javaHome, "bin" + File.separator + "java")
}
