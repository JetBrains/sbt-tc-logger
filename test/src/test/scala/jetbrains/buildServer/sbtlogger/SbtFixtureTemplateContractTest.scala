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

  /**
   * Required expected-output fixtures are regexes, but flow IDs are protocol relationships rather than arbitrary text.
   * Keep that relationship explicit with a file-local symbolic token. `excludes.txt` deliberately remains a plain regex
   * fixture, so it is excluded from this contract.
   */
  @Test
  def everyFlowBearingRequiredFixtureMessageUsesASymbolicFlowId(): Unit = {
    val root = IntegrationTestLayout.repoRoot().toPath.resolve("test/testdata")
    val violations = fixtureTextFiles(root)
      .filterNot(_.getFileName.toString == "excludes.txt")
      .flatMap { file =>
        Files.readAllLines(file).asScala.zipWithIndex.collect {
          case (line, index) if line.contains("flowId='.*'") =>
            s"${root.relativize(file)}:${index + 1}: flowId='.*' is not permitted: $line"
          case (line, index) if flowBearingServiceMessage(line) && !hasValidFlowIdPlaceholder(line) =>
            s"${root.relativize(file)}:${index + 1}: missing flowId='<flowIdN>' placeholder: $line"
        }
      }

    Assert.assertTrue(
      s"""Every flow-bearing TeamCity message in a required fixture must use a symbolic flow-ID placeholder.
         |Violations:
         |${violations.mkString(System.lineSeparator())}
         |""".stripMargin,
      violations.isEmpty
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

  private def fixtureTextFiles(testDataRoot: Path): Seq[Path] = {
    val files = Files.walk(testDataRoot)
    try {
      files.iterator.asScala
        .filter(path => Files.isRegularFile(path))
        .filter(_.getFileName.toString.endsWith(".txt"))
        .toSeq
    }
    finally files.close()
  }

  private def flowBearingServiceMessage(line: String): Boolean = {
    val prefix = "##teamcity\\["
    val name = line.stripPrefix(prefix).takeWhile(character => character != ' ' && character != ']')
    Set(
      "compilationStarted",
      "compilationFinished",
      "message",
      "testSuiteStarted",
      "testSuiteFinished",
      "testStarted",
      "testFinished",
      "testFailed",
      "testIgnored"
    ).contains(name)
  }

  private def hasValidFlowIdPlaceholder(line: String): Boolean =
    """flowId='<flowId[1-9][0-9]*>'""".r.findFirstIn(line).nonEmpty
}
