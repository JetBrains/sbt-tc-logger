package org.jetbrains.sbt.integrationTests

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor, StandardCopyOption}

import scala.io.Source

/**
 * @note copied from sbt-structure `org.jetbrains.sbt.integrationTests.utils.FileUtils`.
 *       Adaptations: package changed; `sbt.io` usage replaced with JDK/Scala APIs; recursive copy/delete helpers added
 *       for this repository's fixture workspace preparation.
 */
object FileUtils {

  def read(file: File): String =
    readLines(file).mkString("\n")

  def readLines(file: File): Seq[String] = {
    val source = Source.fromFile(file, StandardCharsets.UTF_8.name())
    try source.getLines().toVector
    finally source.close()
  }

  def createTempFile(prefix: String, suffix: String): File = {
    val file = Files.createTempFile(prefix, suffix).toFile
    file.deleteOnExit()
    file
  }

  def createTempDirectory(prefix: String): File = {
    val file = Files.createTempDirectory(prefix).toFile
    file.deleteOnExit()
    file
  }

  def writeStringToFile(file: File, content: String): Unit = {
    Files.writeString(file.toPath, content, StandardCharsets.UTF_8)
  }

  def writeLinesTo(file: File, lines: String*): Unit = {
    Files.write(file.toPath, lines.mkString("", System.lineSeparator(), System.lineSeparator()).getBytes(StandardCharsets.UTF_8))
  }

  def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      Files.walkFileTree(path, new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          if (exc != null) throw exc
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }
      })
    }
  }

  def copyDirectorySkipping(source: Path, target: Path, skipDirectoryName: String): Unit = {
    Files.walkFileTree(source, new SimpleFileVisitor[Path] {
      override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
        if (dir != source && dir.getFileName.toString == skipDirectoryName) {
          FileVisitResult.SKIP_SUBTREE
        }
        else {
          Files.createDirectories(target.resolve(source.relativize(dir)))
          FileVisitResult.CONTINUE
        }
      }

      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        FileVisitResult.CONTINUE
      }
    })
  }

  extension (file: File) {
    def /(relativePath: String): File = new File(file, relativePath)
  }


  def normalisePathSeparator(path: String): String =
    path.replace('\\', '/')

  def normalisedAbsolutePath(file: File): String =
    normalisePathSeparator(file.getAbsolutePath)
}
