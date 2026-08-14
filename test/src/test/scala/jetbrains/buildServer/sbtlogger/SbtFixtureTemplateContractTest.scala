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

  @Test
  def scalaTestPassAndFailureFixturesAssertSeparateSuites(): Unit = {
    val root = IntegrationTestLayout.repoRoot().toPath

    SbtTestsRuntime.All.map(_.testDataRelativePath).distinct.foreach { testDataRoot =>
      val fixture = root.resolve(testDataRoot).resolve("testSupport/ScalaTest_PassAndFailure")
      val exampleOutput = Files.readString(fixture.resolve("output.txt"))
      val listOutput = Files.readString(fixture.resolve("output1.txt"))

      Assert.assertTrue(
        s"$testDataRoot ScalaTest output.txt must assert ExampleSpec test events.",
        exampleOutput.contains("testStarted name='ExampleSpec.")
      )
      Assert.assertFalse(
        s"$testDataRoot ScalaTest output.txt must not assert ListFlatSpec test events.",
        exampleOutput.contains("testStarted name='ListFlatSpec.")
      )
      Assert.assertTrue(
        s"$testDataRoot ScalaTest output1.txt must assert ListFlatSpec test events.",
        listOutput.contains("testStarted name='ListFlatSpec.")
      )
      Assert.assertFalse(
        s"$testDataRoot ScalaTest output1.txt must not assert ExampleSpec test events.",
        listOutput.contains("testStarted name='ExampleSpec.")
      )
      Assert.assertNotEquals(
        s"$testDataRoot ScalaTest expected-output fixtures must not be duplicates.",
        exampleOutput,
        listOutput
      )
    }
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
