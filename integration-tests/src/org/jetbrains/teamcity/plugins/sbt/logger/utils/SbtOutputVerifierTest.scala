package org.jetbrains.teamcity.plugins.sbt.logger.utils

import org.jetbrains.sbt.integrationTests.FileUtils
import org.junit.{Assert, Test}

import java.io.File

class SbtOutputVerifierTest {
  private val context = TranscriptContext(
    repoRoot = new File("/repo"),
    workDir = new File("/repo/target/integration-tests/work/profile/scenario"),
    sbtGlobalBase = new File("/repo/target/integration-tests/global/profile/scenario"),
    sbtBootDirectory = new File("/teamcity-cache/integration-test-sbt-boot/profile"),
    sbtCoursierHome = new File("/teamcity-cache/integration-test-coursier/profile"),
    sbtIvyHome = new File("/repo/target/integration-test-ivy/profile"),
    javaHome = new File("/jdks/17"),
    loggerVersion = "2026.1-test"
  )

  @Test def exactComparisonRejectsMissingExtraReorderedAndAlteredLines(): Unit = {
    val golden = goldenFile("alpha", "beta", "gamma")
    verify(Vector("alpha", "beta", "gamma"), golden)
    Seq(
      Vector("alpha", "gamma"),
      Vector("alpha", "extra", "beta", "gamma"),
      Vector("beta", "alpha", "gamma"),
      Vector("alpha", "changed", "gamma")
    ).foreach(actual => expectAssertionError(verify(actual, golden)))
  }

  @Test def rawTeamCityAttributeReorderingFails(): Unit = {
    val golden = goldenFile("##teamcity[message text='hello' status='NORMAL']")
    verify(Vector("##teamcity[message text='hello' status='NORMAL']"), golden)
    expectAssertionError {
      verify(Vector("##teamcity[message status='NORMAL' text='hello']"), golden)
    }
  }

  @Test def typedPlaceholdersValidateBindingsDistinctFlowsAndPathSuffixes(): Unit = {
    val golden = goldenFile(
      "##teamcity[testStarted name='a' flowId='{{flow:first}}']",
      "##teamcity[testFinished name='a' duration='{{duration:test}}' flowId='{{flow:first}}']",
      "##teamcity[testStarted name='b' flowId='{{flow:second}}']",
      "source={{path:work-dir}}/src/Test.scala boot={{path:sbt-boot-directory}}/scala-3/library.jar cache={{path:sbt-coursier-home}}/cache version={{logger-version}} build={{build-id:root}}",
      "again={{build-id:root}}"
    )
    verify(Vector(
      "##teamcity[testStarted name='a' flowId='17']",
      "##teamcity[testFinished name='a' duration='9' flowId='17']",
      "##teamcity[testStarted name='b' flowId='18']",
      "source=/repo/target/integration-tests/work/profile/scenario/src/Test.scala boot=/teamcity-cache/integration-test-sbt-boot/profile/scala-3/library.jar cache=/teamcity-cache/integration-test-coursier/profile/cache version=2026.1-test build=-42",
      "again=-42"
    ), golden)

    expectAssertionError(verify(Vector(
      "##teamcity[testStarted name='a' flowId='17']",
      "##teamcity[testFinished name='a' duration='9' flowId='99']",
      "##teamcity[testStarted name='b' flowId='18']",
      "source=/repo/target/integration-tests/work/profile/scenario/src/Test.scala boot=/teamcity-cache/integration-test-sbt-boot/profile/scala-3/library.jar cache=/teamcity-cache/integration-test-coursier/profile/cache version=2026.1-test build=-42",
      "again=-42"
    ), golden))
    expectAssertionError(verify(Vector(
      "##teamcity[testStarted name='a' flowId='17']",
      "##teamcity[testFinished name='a' duration='9' flowId='17']",
      "##teamcity[testStarted name='b' flowId='17']",
      "source=/repo/target/integration-tests/work/profile/scenario/src/Test.scala boot=/teamcity-cache/integration-test-sbt-boot/profile/scala-3/library.jar cache=/teamcity-cache/integration-test-coursier/profile/cache version=2026.1-test build=-42",
      "again=-42"
    ), golden))
    expectAssertionError(verify(Vector(
      "##teamcity[testStarted name='a' flowId='17']",
      "##teamcity[testFinished name='a' duration='9' flowId='17']",
      "##teamcity[testStarted name='b' flowId='18']",
      "source=/tmp/other/src/Test.scala cache=/teamcity-cache/integration-test-coursier/profile/cache version=2026.1-test build=-42",
      "again=-42"
    ), golden))
  }

  @Test def unknownOrUntypedPlaceholderIsRejected(): Unit = {
    expectIllegalArgument(verify(Vector("anything"), goldenFile("{{regex:.*}}")))
    expectIllegalArgument(verify(Vector("anything"), goldenFile("{{path:not-a-root}}")))
  }

  @Test def typedPlaceholdersKeepNamedBuildIdsDistinct(): Unit = {
    val golden = goldenFile(
      "first={{build-id:first}}",
      "second={{build-id:second}}"
    )

    verify(Vector("first=41", "second=42"), golden)
    expectAssertionError(verify(Vector("first=41", "second=41"), golden))
  }

  @Test def unorderedCompilationLanesKeepDependencyAndCompilerBuildIdsBoundToTheSameModule(): Unit = {
    val golden = goldenFile(
      "[[unordered]]",
      "[[lane:root]]",
      "##teamcity[message status='NORMAL' flowId='{{build-id:root}}:global:dependency' text='|[debug|] not up to date. inChanged = true, force = false']",
      "##teamcity[message status='NORMAL' flowId='{{build-id:root}}:global:dependency' text='|[debug|] Updating ...']",
      "##teamcity[message status='NORMAL' flowId='{{build-id:root}}:global:dependency' text='|[debug|] Done updating ']",
      "##teamcity[compilationStarted compiler='Scala compiler |[root|]' flowId='{{build-id:root}}:compile:compiler']",
      "[[/lane]]",
      "[[lane:project1]]",
      "##teamcity[message status='NORMAL' flowId='{{build-id:project1}}:global:dependency' text='|[debug|] not up to date. inChanged = true, force = false']",
      "##teamcity[message status='NORMAL' flowId='{{build-id:project1}}:global:dependency' text='|[debug|] Updating project1...']",
      "##teamcity[message status='NORMAL' flowId='{{build-id:project1}}:global:dependency' text='|[debug|] Done updating project1']",
      "##teamcity[compilationStarted compiler='Scala compiler |[project1|]' flowId='{{build-id:project1}}:compile:compiler']",
      "[[/lane]]",
      "[[lane:project2]]",
      "##teamcity[message status='NORMAL' flowId='{{build-id:project2}}:global:dependency' text='|[debug|] not up to date. inChanged = true, force = false']",
      "##teamcity[message status='NORMAL' flowId='{{build-id:project2}}:global:dependency' text='|[debug|] Updating project2...']",
      "##teamcity[message status='NORMAL' flowId='{{build-id:project2}}:global:dependency' text='|[debug|] Done updating project2']",
      "##teamcity[compilationStarted compiler='Scala compiler |[project2|]' flowId='{{build-id:project2}}:compile:compiler']",
      "[[/lane]]",
      "[[/unordered]]"
    )
    val valid = Vector(
      dependency("1512025224"),
      dependency("-353998860"),
      updating("project1", "-353998860"),
      updating("project2", "1512025224"),
      dependency("2027211914"),
      updating("", "2027211914"),
      updated("project1", "-353998860"),
      updated("", "2027211914"),
      updated("project2", "1512025224"),
      compilationStarted("root", "2027211914"),
      compilationStarted("project1", "-353998860"),
      compilationStarted("project2", "1512025224")
    )

    verify(valid, golden)
    expectAssertionError(verify(valid.updated(1, dependency("2027211914")), golden))
    expectAssertionError(verify(valid.map(_.replace("-353998860", "2027211914")), golden))
  }

  @Test def malformedTeamCityLookingLinesAreRejectedByOfficialParser(): Unit = {
    expectAssertionError(verify(Vector("prefix ##teamcity[message text='x']"), goldenFile("prefix ##teamcity[message text='x']")))
    expectAssertionError(verify(Vector("##teamcity[testStarted flowId='1']"), goldenFile("##teamcity[testStarted flowId='1']")))
  }

  @Test def unorderedBlockAcceptsEveryLaneInterleaving(): Unit = {
    val golden = unorderedGolden()
    interleavings(Vector("a1", "a2"), Vector("b1", "b2")).foreach { middle =>
      verify(Vector("before") ++ middle ++ Vector("after"), golden)
    }
  }

  @Test def unorderedBlockRejectsMissingDuplicateCrossLaneOrderAndUnexpectedLines(): Unit = {
    val golden = unorderedGolden()
    Seq(
      Vector("before", "a1", "b1", "a2", "after"),
      Vector("before", "a1", "b1", "a1", "a2", "b2", "after"),
      Vector("before", "a2", "a1", "b1", "b2", "after"),
      Vector("before", "a1", "surprise", "a2", "b1", "b2", "after")
    ).foreach(actual => expectAssertionError(verify(actual, golden)))
  }

  @Test def everyNoiseRecognizerRejectsUnrelatedOutput(): Unit = {
    val samples = Seq(
      "sbt-task-summary" -> "[success] elapsed time: 1 s, cache 25%, 3 onsite tasks",
      "sbt-debug-line" -> "[debug] Evaluating tasks: Compile / compile",
      "zinc-debug-message" -> "##teamcity[message status='NORMAL' flowId='1:compile:compiler' text='|[debug|] |[zinc|] IncrementalCompile -----------']",
      "framework-stack-tail" -> "\tat org.scalatest.Suite.run(Suite.scala:1)",
      "dependency-resource-outcome" -> "##teamcity[message status='NORMAL' flowId='teamcity-sbt-dependency-resolution' text='|[root / global|] local cache hit https://repo1.maven.org/a.jar']"
    )
    samples.foreach { case (name, accepted) =>
      verify(Vector(accepted), goldenFile(s"[[noise:$name]]"))
      expectAssertionError(verify(Vector("fixture says something unrelated"), goldenFile(s"[[noise:$name]]")))
    }
  }

  @Test def compilerBridgeRecognizerAcceptsOnlyACompleteColdCacheBlockOrItsExplicitAbsence(): Unit = {
    val golden = goldenFile("[[noise:sbt-compiler-bridge]]")
    val coldBridge = Vector(
      "[info] Non-compiled module 'compiler-bridge_2.12' for Scala 2.12.20. Compiling...",
      "[info]   Compilation completed in 4.321s."
    )
    verify(Vector.empty, golden)
    verify(coldBridge, golden)
    expectAssertionError(verify(coldBridge.take(1), golden))
    expectAssertionError(verify(Vector("[info] fixture output", coldBridge(1)), golden))
  }

  @Test def compilerBridgeRecognizerCanBeOptionalInsideAnUnorderedLane(): Unit = {
    val golden = goldenFile(
      "[[unordered]]",
      "[[lane:compile]]", "compile-started", "[[noise:sbt-compiler-bridge]]", "compile-finished", "[[/lane]]",
      "[[lane:other]]", "other", "[[/lane]]",
      "[[/unordered]]"
    )
    val coldBridge = Vector(
      "[info] Non-compiled module 'compiler-bridge_2.12' for Scala 2.12.20. Compiling...",
      "[info]   Compilation completed in 4.321s."
    )

    verify(Vector("other", "compile-started", "compile-finished"), golden)
    verify(Vector("compile-started") ++ coldBridge ++ Vector("other", "compile-finished"), golden)
    expectAssertionError(verify(Vector("compile-started", coldBridge.head, "other", "compile-finished"), golden))
  }

  @Test def explicitEmptyTranscriptIsRequiredAndEnforced(): Unit = {
    verify(Vector.empty, goldenFile("[[expect-empty]]"))
    expectAssertionError(verify(Vector("unexpected"), goldenFile("[[expect-empty]]")))
    expectIllegalArgument(verify(Vector.empty, goldenFile()))
  }

  @Test def candidateRenderingRoundTripsWithoutTouchingSourceGoldens(): Unit = {
    val actual = Vector(
      "##teamcity[compilationStarted compiler='Scala compiler' flowId='123:compile:compiler']",
      "##teamcity[message status='NORMAL' flowId='123:compile:compiler' text='source /repo/target/integration-tests/work/profile/scenario/src/A.scala']",
      "##teamcity[testStarted name='test' flowId='17']",
      "##teamcity[testFinished name='test' duration='8' flowId='17']",
      "version 2026.1-test",
      "[success] elapsed time: 1 s, cache 0%, 2 onsite tasks"
    )
    val destination = FileUtils.createTempFile("candidate", ".txt")
    SbtOutputVerifier.writeCandidate(actual, destination, context)
    Assert.assertTrue(FileUtils.read(destination).contains("{{path:work-dir}}/src/A.scala"))
    Assert.assertTrue(FileUtils.read(destination).contains("{{build-id:root}}:compile:compiler"))
    Assert.assertTrue(FileUtils.read(destination).contains("{{flow:test}}"))
    verify(actual, destination)
  }

  @Test def candidateRenderingUsesExactCoursierHomeAndPerSuiteFlows(): Unit = {
    val actual = Vector(
      "cache=/teamcity-cache/integration-test-coursier/profile/cache/v1/https/repo1.maven.org/example.jar",
      "##teamcity[testSuiteStarted name='suites.NonParallelSuite' flowId='17']",
      "##teamcity[testStarted name='suites.NonParallelSuite.test' flowId='17']",
      "##teamcity[testSuiteFinished name='suites.NonParallelSuite' flowId='17']",
      "##teamcity[testSuiteStarted name='tests.ParallelTest' flowId='18']",
      "##teamcity[testStarted name='tests.ParallelTest.test' flowId='18']",
      "##teamcity[testSuiteFinished name='tests.ParallelTest' flowId='18']"
    )
    val destination = FileUtils.createTempFile("semantic-candidate", ".txt")

    SbtOutputVerifier.writeCandidate(actual, destination, context)

    Assert.assertEquals(
      Vector(
        "cache={{path:sbt-coursier-home}}/cache/v1/https/repo1.maven.org/example.jar",
        "##teamcity[testSuiteStarted name='suites.NonParallelSuite' flowId='{{flow:suite-non-parallel}}']",
        "##teamcity[testStarted name='suites.NonParallelSuite.test' flowId='{{flow:suite-non-parallel}}']",
        "##teamcity[testSuiteFinished name='suites.NonParallelSuite' flowId='{{flow:suite-non-parallel}}']",
        "##teamcity[testSuiteStarted name='tests.ParallelTest' flowId='{{flow:direct-parallel}}']",
        "##teamcity[testStarted name='tests.ParallelTest.test' flowId='{{flow:direct-parallel}}']",
        "##teamcity[testSuiteFinished name='tests.ParallelTest' flowId='{{flow:direct-parallel}}']"
      ),
      FileUtils.readLines(destination).toVector
    )
    verify(actual, destination)
  }

  @Test def javaSourcesInputMappingsAcceptOnlyTheKnownMappingsInAnyClassFileOrder(): Unit = {
    val golden = goldenFile(
      "##teamcity[message status='NORMAL' flowId='{{build-id:root}}:compile:general:packageBin' text='{{input-file-mappings:java-sources}}']"
    )
    val inExpectedOrder = javaSourcesMapping(Vector("HelloScala.class", "HelloWorld.class", "HelloScala$.class"))
    val reordered = javaSourcesMapping(Vector("HelloWorld.class", "HelloScala$.class", "HelloScala.class"))

    verify(Vector(packageMappingMessage(inExpectedOrder)), golden)
    verify(Vector(packageMappingMessage(reordered)), golden)
    verify(Vector(packageMappingMessage(scala3JavaSourcesMapping(Vector(
      "com/jetbrains/sbt/test/HelloScala.tasty",
      "com/jetbrains/sbt/test/HelloWorld.class",
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloScala$.class"
    )))), golden)
    expectAssertionError(verify(Vector(packageMappingMessage(javaSourcesMapping(Vector(
      "HelloScala.class", "HelloScala.class", "HelloWorld.class"
    )))), golden))
    expectAssertionError(verify(Vector(packageMappingMessage(javaSourcesMapping(Vector(
      "HelloScala.class", "HelloWorld.class", "Unexpected.class"
    )))), golden))

    val candidate = FileUtils.createTempFile("input-mappings-candidate", ".txt")
    SbtOutputVerifier.writeCandidate(Vector(packageMappingMessage(reordered)), candidate, context)
    Assert.assertTrue(FileUtils.read(candidate).contains("text='{{input-file-mappings:java-sources}}'"))
  }

  @Test def longFrameworkStackTailIsRecognizedWithoutRegexBacktracking(): Unit = {
    val frameworkTail = (1 to 300).map(index => s"|n\tat org.junit.runners.ParentRunner.run(ParentRunner.scala:$index)").mkString
    val actual = Vector(
      s"##teamcity[testFailed name='fixture.Test.fails' details='java.lang.AssertionError: boom|n\tat fixture.Test.$$anonfun$$fails(Test.scala:7)$frameworkTail' flowId='17']"
    )
    val candidate = FileUtils.createTempFile("stack-candidate", ".txt")
    SbtOutputVerifier.writeCandidate(actual, candidate, context)
    Assert.assertTrue(FileUtils.read(candidate).contains("{{framework-stack-tail:junit}}"))
    verify(actual, candidate)
  }

  @Test def handshakeBoundsTranscriptAndCapturesVersion(): Unit = {
    val bounded = SbtTranscriptBoundary.extract(
      "startup\nTeamCity sbt logger\n  Version: v1\n  TeamCity: 9.0.TEST\n  Status: active\n  Preserve SBT console: false (default)\n  Use TeamCity test result logger: true (default)\n  Show test-task output: true (default)\n  Detailed dependency resolution: false (default)\nafter\n",
      activeHandshake
    )
    Assert.assertEquals("v1", bounded.loggerVersion)
    Assert.assertEquals(Vector("after"), bounded.lines)
  }

  @Test def handshakeRejectsMissingMalformedAndPreBoundaryTeamCityOutput(): Unit = {
    expectAssertionError(SbtTranscriptBoundary.extract("startup\n", activeHandshake))
    expectAssertionError(SbtTranscriptBoundary.extract(
      "TeamCity sbt logger\n  Version: \n  TeamCity: 9.0.TEST\n  Status: active\n  Preserve SBT console: false (default)\n  Use TeamCity test result logger: true (default)\n  Show test-task output: true (default)\n  Detailed dependency resolution: false (default)\n",
      activeHandshake
    ))
    expectAssertionError(SbtTranscriptBoundary.extract(
      "##teamcity[message text='too early']\nTeamCity sbt logger\n  Version: v1\n  TeamCity: 9.0.TEST\n  Status: active\n  Preserve SBT console: false (default)\n  Use TeamCity test result logger: true (default)\n  Show test-task output: true (default)\n  Detailed dependency resolution: false (default)\n",
      activeHandshake
    ))
  }

  private val activeHandshake = SbtTranscriptBoundary.ExpectedHandshake(
    teamCityVersion = Some("9.0.TEST"),
    preserveConsole = false,
    useTeamCityTestResultLogger = true,
    showTestTaskOutput = true,
    detailedDependencyResolution = false,
    renderObjectEventDetails = false
  )

  private def unorderedGolden(): File = goldenFile(
    "before",
    "[[unordered]]",
    "[[lane:a]]", "a1", "a2", "[[/lane]]",
    "[[lane:b]]", "b1", "b2", "[[/lane]]",
    "[[/unordered]]",
    "after"
  )

  private def interleavings(left: Vector[String], right: Vector[String]): Vector[Vector[String]] =
    if (left.isEmpty) Vector(right)
    else if (right.isEmpty) Vector(left)
    else interleavings(left.tail, right).map(left.head +: _) ++ interleavings(left, right.tail).map(right.head +: _)

  private def goldenFile(lines: String*): File = {
    val file = FileUtils.createTempFile("exact-transcript", ".txt")
    FileUtils.writeStringToFile(file, lines.mkString("\n"))
    file
  }

  private def packageMappingMessage(text: String): String =
    s"##teamcity[message status='NORMAL' flowId='41:compile:general:packageBin' text='$text']"

  private def dependency(buildId: String): String =
    s"##teamcity[message status='NORMAL' flowId='$buildId:global:dependency' text='|[debug|] not up to date. inChanged = true, force = false']"

  private def updating(project: String, buildId: String): String =
    s"##teamcity[message status='NORMAL' flowId='$buildId:global:dependency' text='|[debug|] Updating $project...']"

  private def updated(project: String, buildId: String): String =
    s"##teamcity[message status='NORMAL' flowId='$buildId:global:dependency' text='|[debug|] Done updating $project']"

  private def compilationStarted(project: String, buildId: String): String =
    s"##teamcity[compilationStarted compiler='Scala compiler |[$project|]' flowId='$buildId:compile:compiler']"

  private def javaSourcesMapping(classFiles: Vector[String]): String = {
    val classesDirectory = s"${context.paths("work-dir")}/target/scala-2.13/classes/"
    val directories = Vector("com", "com/jetbrains", "com/jetbrains/sbt", "com/jetbrains/sbt/test")
    val entries = directories ++ classFiles
    (Vector("|[debug|] Input file mappings:") ++ entries.flatMap { entry =>
      Vector(s"|[debug|] \t$entry", s"|[debug|] \t  $classesDirectory$entry")
    }).mkString("|n")
  }

  private def scala3JavaSourcesMapping(entries: Vector[String]): String = {
    val classesDirectory = s"${context.paths("work-dir")}/target/out/jvm/scala-3.8.4/java-sources-compile-run/classes/"
    (Vector("|[debug|] Input file mappings:") ++ entries.flatMap { entry =>
      Vector(s"|[debug|] \t$entry", s"|[debug|] \t  $classesDirectory$entry")
    }).mkString("|n")
  }

  private def verify(actual: Vector[String], golden: File): Unit =
    SbtOutputVerifier.verify(actual, golden, context)

  private def expectAssertionError(body: => Any): AssertionError = expect[AssertionError](body)
  private def expectIllegalArgument(body: => Any): IllegalArgumentException = expect[IllegalArgumentException](body)

  private def expect[T <: Throwable](body: => Any)(using tag: reflect.ClassTag[T]): T = {
    try {
      body
      Assert.fail(s"Expected ${tag.runtimeClass.getSimpleName}")
      throw new AssertionError("unreachable")
    } catch {
      case error if tag.runtimeClass.isInstance(error) => error.asInstanceOf[T]
    }
  }
}
