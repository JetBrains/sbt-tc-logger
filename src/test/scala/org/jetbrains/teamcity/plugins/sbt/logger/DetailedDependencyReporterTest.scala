package org.jetbrains.teamcity.plugins.sbt.logger

import java.io.{ByteArrayOutputStream, PrintStream}

import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

/** Fast deterministic coverage for the Coursier callback presentation, independent of the local artifact cache. */
class DetailedDependencyReporterTest {

  @Test
  def reportsFinalResourceOutcomesWithSanitisedUrlsAndOneWaveFooter(): Unit = {
    val output = captureOutput {
      val appender = new TCLogAppender
      appender.detailedDependencyResolutionStarted("module-a", "Compile")
      appender.detailedDependencyFoundLocally(
        "module-a",
        "Compile",
        "https://user:secret@example.test/repository/artifact.pom?token=secret#fragment"
      )
      appender.detailedDependencyDownloading("module-a", "Compile", "https://example.test/repository/artifact.jar")
      appender.detailedDependencyDownloadLength("module-a", "Compile", "https://example.test/repository/artifact.jar", 1536L)
      appender.detailedDependencyDownloaded("module-a", "Compile", "https://example.test/repository/artifact.jar", success = true)
      appender.detailedDependencyDownloading("module-a", "Compile", "https://example.test/repository/missing.jar")
      appender.detailedDependencyDownloaded("module-a", "Compile", "https://example.test/repository/missing.jar", success = false)
      appender.detailedDependencyReportCacheHit("module-a", "Compile")
      appender.detailedDependencyResolutionFinished("module-a", "Compile")
    }

    assertTrue(output.contains("https://example.test/repository/artifact.pom"))
    assertFalse(output.contains("user:secret"))
    assertFalse(output.contains("token=secret"))
    assertFalse(output.contains("#fragment"))
    assertTrue(output.contains("local cache hit"))
    assertTrue(output, output.contains("downloaded https://example.test/repository/artifact.jar (1.5 KiB,"))
    assertTrue(output.contains("status='WARNING'"))
    assertTrue(output.contains("failed download attempt https://example.test/repository/missing.jar"))
    assertFalse(output.contains("status='ERROR'"))
    assertTrue(output.contains("sbt update report cache hit"))
    assertTrue(output.contains("Dependency resolution finished in"))
    assertTrue(output.indexOf("Dependency resolution finished in") < output.indexOf("blockClosed"))
  }

  @Test
  def mergesConcurrentUpdatesIntoOneBlockAndClosesAfterTheLastOne(): Unit = {
    val output = captureOutput {
      val appender = new TCLogAppender
      appender.detailedDependencyResolutionStarted("module-a", "Compile")
      appender.detailedDependencyResolutionStarted("module-b", "Test")
      appender.detailedDependencyResolutionFinished("module-a", "Compile")
      appender.detailedDependencyResolutionFinished("module-b", "Test")
    }

    assertEquals(1, occurrences(output, "##teamcity[blockOpened name='Dependency resolution'"))
    assertEquals(1, occurrences(output, "##teamcity[blockClosed name='Dependency resolution'"))
    assertTrue(output.indexOf("blockClosed") > output.indexOf("Dependency resolution finished in"))
  }

  private def captureOutput(action: => Unit): String = {
    val bytes = new ByteArrayOutputStream
    Console.withOut(new PrintStream(bytes)) {
      action
    }
    bytes.toString("UTF-8")
  }

  private def occurrences(text: String, fragment: String): Int =
    text.sliding(fragment.length).count(_ == fragment)
}
