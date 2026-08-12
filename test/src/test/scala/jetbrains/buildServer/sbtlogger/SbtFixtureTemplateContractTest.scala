package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.IntegrationTestLayout
import org.jetbrains.sbt.integrationTests.SbtFixtureWorkspace
import org.junit.{Assert, Test}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Guards the reusable source-fixture contract for both SBT plugin binary-version lines. */
class SbtFixtureTemplateContractTest {

  @Test
  def everyFixtureProjectUsesTheSbtVersionTemplate(): Unit = {
    val root = IntegrationTestLayout.repoRoot().toPath.resolve("test/testdata")

    Seq("1.0", "2.0").foreach { line =>
      val propertiesFiles = buildPropertiesFiles(root.resolve(line))
      Assert.assertEquals(s"Unexpected number of fixture projects under $line", 19, propertiesFiles.size)
      propertiesFiles.foreach { propertiesFile =>
        Assert.assertEquals(
          s"Unexpected SBT version template in $propertiesFile",
          s"sbt.version=${SbtFixtureWorkspace.SbtVersionTemplate}",
          Files.readString(propertiesFile).trim
        )
      }
    }
  }

  private def buildPropertiesFiles(testDataRoot: Path): Seq[Path] = {
    val files = Files.walk(testDataRoot)
    try {
      files.iterator.asScala
        .filter(path => Files.isRegularFile(path))
        .filter(_.getFileName.toString == "build.properties")
        .filter(_.getParent.getFileName.toString == "project")
        .toSeq
    }
    finally files.close()
  }
}
