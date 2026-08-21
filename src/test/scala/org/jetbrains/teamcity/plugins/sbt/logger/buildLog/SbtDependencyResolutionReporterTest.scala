package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import java.io.{ByteArrayOutputStream, PrintStream}

import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.StandardOutputTeamCityServiceMessageWriter
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

/** Fast deterministic coverage for Coursier callback presentation, independent of the local artifact cache. */
class SbtDependencyResolutionReporterTest {
  @Test
  def reportsFinalResourceOutcomesWithSanitisedUrlsAndOneWaveFooter(): Unit = {
    val output = captureOutput { reporter =>
      reporter.started()
      reporter.foundLocally("module-a", "Compile", "https://user:secret@example.test/repository/artifact.pom?token=secret#fragment")
      reporter.downloading("module-a", "Compile", "https://example.test/repository/artifact.jar")
      reporter.downloadLength("module-a", "Compile", "https://example.test/repository/artifact.jar", 1536L)
      reporter.downloaded("module-a", "Compile", "https://example.test/repository/artifact.jar", success = true)
      reporter.downloading("module-a", "Compile", "https://example.test/repository/missing.jar")
      reporter.downloaded("module-a", "Compile", "https://example.test/repository/missing.jar", success = false)
      reporter.reportCacheHit("module-a", "Compile")
      reporter.finished()
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
    val output = captureOutput { reporter =>
      reporter.started()
      reporter.started()
      reporter.finished()
      reporter.finished()
    }

    assertEquals(1, occurrences(output, "##teamcity[blockOpened name='Dependency resolution'"))
    assertEquals(1, occurrences(output, "##teamcity[blockClosed name='Dependency resolution'"))
    assertTrue(output.indexOf("blockClosed") > output.indexOf("Dependency resolution finished in"))
  }

  private def captureOutput(action: SbtDependencyResolutionReporter => Unit): String = {
    val bytes = new ByteArrayOutputStream
    action(new SbtDependencyResolutionReporter(new StandardOutputTeamCityServiceMessageWriter(new PrintStream(bytes))))
    bytes.toString("UTF-8")
  }

  private def occurrences(text: String, fragment: String): Int =
    text.sliding(fragment.length).count(_ == fragment)
}
