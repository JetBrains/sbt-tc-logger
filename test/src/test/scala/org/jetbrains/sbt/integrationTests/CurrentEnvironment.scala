package org.jetbrains.sbt.integrationTests

import java.io.File

/**
 * @note copied from sbt-structure `org.jetbrains.sbt.integrationTests.utils.CurrentEnvironment`.
 *       Adaptations: package changed; global sbt-structure directories and option building were omitted; Java selection
 *       is auto-only and returns the current Java for sbt 1.3+ and a discovered Java 8/11 for older sbt runtimes.
 */
object CurrentEnvironment {

  val OsName: String = System.getProperty("os.name").toLowerCase
  val UserHome: File = new File(System.getProperty("user.home")).getCanonicalFile.ensuring(_.exists())
  val WorkingDir: File = new File(".").getCanonicalFile

  val CurrentJavaHome: File = new File(System.getProperty("java.home")).getCanonicalFile
  val CurrentJavaExecutablePath: String = javaExecutable(CurrentJavaHome).getCanonicalPath

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

  lazy val JavaOldHome: File =
    findJvmInstallation("1.8")
      .orElse(findJvmInstallation("8"))
      .orElse(findJvmInstallation("11"))
      .getOrElse {
        throw new IllegalStateException(
          "Java 8 or 11 was not found in default locations:\n" + PossibleJvmLocations.mkString("\n")
        )
      }

  lazy val JavaOldExecutablePath: String = javaExecutable(JavaOldHome).getCanonicalPath

  def javaExecutableFor(sbtVersion: Version): String =
    if (sbtVersion >= Version("1.3.0")) CurrentJavaExecutablePath
    else JavaOldExecutablePath

  def javaHomeFor(sbtVersion: Version): File =
    if (sbtVersion >= Version("1.3.0")) CurrentJavaHome
    else JavaOldHome

  private def findJvmInstallation(javaVersion: String): Option[File] = {
    val jvmFolder = PossibleJvmLocations
      .flatMap { folder =>
        val dirs = Option(folder.listFiles()).getOrElse(Array.empty[File]).filter(_.isDirectory)
        dirs.filter(_.getName.contains(javaVersion))
      }
      .headOption
      .map { root =>
        if (OsName.contains("mac"))
          new File(root, "Contents/Home")
        else
          root
      }

    jvmFolder.filter(javaExecutable(_).isFile)
  }

  private def javaExecutable(javaHome: File): File =
    new File(javaHome, "bin" + File.separator + "java")
}
