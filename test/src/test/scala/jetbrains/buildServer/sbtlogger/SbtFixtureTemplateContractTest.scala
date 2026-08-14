package jetbrains.buildServer.sbtlogger

import jetbrains.buildServer.sbtlogger.utils.IntegrationTestLayout
import org.jetbrains.sbt.integrationTests.SbtFixtureWorkspace
import org.junit.{Assert, Test}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Guards the reusable and version-specific source-fixture contracts. */
class SbtFixtureTemplateContractTest {

  @Test
  def everyDeclaredSbtVersionUsesTheTemplate(): Unit = {
    val root = IntegrationTestLayout.repoRoot().toPath.resolve("test/testdata")
    val declarations = buildPropertiesFiles(root)
      .map(propertiesFile => root.relativize(propertiesFile) -> SbtFixtureWorkspace.sbtVersionValues(Files.readString(propertiesFile)))
      .filter { case (_, values) => values.nonEmpty }
      .sortBy { case (path, _) => path.toString }

    Assert.assertFalse(
      s"No sbt.version declarations were found in build.properties files under $root",
      declarations.isEmpty
    )

    val invalidDeclarations = declarations.filter { case (_, values) => values != Seq(SbtFixtureWorkspace.SbtVersionTemplate) }
    Assert.assertTrue(
      s"""Every declared sbt.version must consist of exactly one ${SbtFixtureWorkspace.SbtVersionTemplate} template value.
         |Invalid declarations:
         |${invalidDeclarations.map { case (path, values) => s"$path: ${values.mkString("[", ", ", "]")}" }.mkString(System.lineSeparator())}
         |""".stripMargin,
      invalidDeclarations.isEmpty
    )
  }

  private def buildPropertiesFiles(testDataRoot: Path): Seq[Path] = {
    val files = Files.walk(testDataRoot)
    try {
      files.iterator.asScala
        .filter(path => Files.isRegularFile(path))
        .filter(_.getFileName.toString == "build.properties")
        .toSeq
    }
    finally files.close()
  }
}
