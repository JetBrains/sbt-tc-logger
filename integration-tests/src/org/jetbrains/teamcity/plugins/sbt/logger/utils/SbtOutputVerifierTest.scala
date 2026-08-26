package org.jetbrains.teamcity.plugins.sbt.logger.utils

import org.jetbrains.sbt.integrationTests.FileUtils
import org.junit.{Assert, Test}

import java.io.File

class SbtOutputVerifierTest {
  private val JavaSourceClassFiles = Vector(
    "com/jetbrains/sbt/test/HelloScala.class",
    "com/jetbrains/sbt/test/HelloScala$.class",
    "com/jetbrains/sbt/test/HelloWorld.class"
  )

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

  @Test def longOrderedTranscriptDoesNotConsumeTheCallStack(): Unit = {
    val lines = Vector.tabulate(12000)(index => s"ordered-line-$index")
    verify(lines, goldenFile(lines*))
  }

  @Test def rawTeamCityAttributeReorderingFails(): Unit = {
    val golden = goldenFile("##teamcity[message text='hello' status='NORMAL']")
    verify(Vector("##teamcity[message text='hello' status='NORMAL']"), golden)
    expectAssertionError {
      verify(Vector("##teamcity[message status='NORMAL' text='hello']"), golden)
    }
  }

  @Test def mismatchDiagnosticsEscapeNestedServiceMessages(): Unit = {
    Seq(
      "##teamcity[message status='NORMAL' text='actual']" ->
        "##teamcity[message status='NORMAL' text='expected']",
      "##teamcity[testStarted name='inner-failure' flowId='17']" ->
        "##teamcity[testStarted name='different' flowId='{{flow:test}}']"
    ).foreach { case (actual, expected) =>
      val error = expectAssertionError(verify(Vector(actual), goldenFile(expected)))
      Assert.assertFalse(error.getMessage.contains("##teamcity["))
      Assert.assertTrue(error.getMessage.contains("@@teamcity["))
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

  @Test def unorderedMatcherAcceptsGeneratedTwoThreeAndFourLaneInterleavings(): Unit = {
    val expectedCounts = Map(2 -> 6, 3 -> 90, 4 -> 2520)
    (2 to 4).foreach { laneCount =>
      val lanes = Vector.tabulate(laneCount) { lane =>
        Vector(s"lane-$lane-start", s"lane-$lane-finish")
      }
      val generated = laneInterleavings(lanes)
      Assert.assertEquals(s"Unexpected generated count for $laneCount lanes", expectedCounts(laneCount), generated.size)
      val golden = lanesGolden(lanes)
      generated.foreach(actual => verify(actual, golden))
    }
  }

  @Test def unorderedMatcherFindsTheValidSixLaneBindingInTheFormer257thCandidate(): Unit = {
    val names = Vector("a", "b", "c", "d", "e", "f")
    val goldenLines =
      Vector("[[unordered]]") ++
        names.flatMap(name => Vector(s"[[lane:$name]]", s"value={{build-id:$name}}", "[[/lane]]")) ++
        Vector(
          "[[/unordered]]",
          "assignment={{build-id:a}},{{build-id:b}},{{build-id:c}},{{build-id:d}},{{build-id:e}},{{build-id:f}}"
        )
    val golden = goldenFile(goldenLines*)

    // The lane-consumption permutation c,a,e,f,b,d is candidate 257 in the old depth-first ordering.
    verify(
      Vector("value=1", "value=2", "value=3", "value=4", "value=5", "value=6", "assignment=2,5,1,6,3,4"),
      golden
    )
  }

  @Test def unorderedMatcherCarriesSharedBindingsAcrossLanesAndIntoTheContinuation(): Unit = {
    val golden = goldenFile(
      "[[unordered]]",
      "[[lane:producer]]", "shared={{build-id:shared}}", "producer={{build-id:shared}}", "[[/lane]]",
      "[[lane:observer]]", "observer={{build-id:shared}}", "[[/lane]]",
      "[[/unordered]]",
      "after={{build-id:shared}}"
    )

    verify(Vector("observer=41", "shared=41", "producer=41", "after=41"), golden)
    expectAssertionError(verify(Vector("observer=42", "shared=41", "producer=41", "after=41"), golden))
    expectAssertionError(verify(Vector("observer=41", "shared=41", "producer=41", "after=42"), golden))
  }

  @Test def unorderedMatcherHandlesIdenticalPrefixesAndDuplicateLinesWithoutDroppingMultiplicity(): Unit = {
    val golden = lanesGolden(Vector(
      Vector("same", "left"),
      Vector("same", "right")
    ), continuation = Vector("after"))

    verify(Vector("same", "left", "same", "right", "after"), golden)
    verify(Vector("same", "same", "right", "left", "after"), golden)
    expectAssertionError(verify(Vector("same", "left", "right", "after"), golden))
    expectAssertionError(verify(Vector("same", "same", "same", "left", "right", "after"), golden))
  }

  @Test def unorderedMatcherAgreesWithBruteForceForSmallDuplicateHeavyCases(): Unit = {
    val cases = Vector(
      Vector(Vector("same", "left"), Vector("same", "right")) -> Vector("same", "left", "right"),
      Vector(Vector("x"), Vector("x"), Vector("y")) -> Vector("x", "y", "z"),
      Vector(Vector("a"), Vector("b"), Vector("a"), Vector("b")) -> Vector("a", "b", "c")
    )

    cases.foreach { case (lanes, alphabet) =>
      val continuation = Vector("after")
      val golden = lanesGolden(lanes, continuation)
      val candidates = sequences(alphabet, lanes.map(_.size).sum)
      candidates.foreach { unorderedActual =>
        val actual = unorderedActual ++ continuation
        val expected = bruteForceMatches(lanes, unorderedActual)
        val accepted = verifierAccepts(actual, golden)
        if (accepted != expected) {
          Assert.fail(s"Matcher/reference disagreement for lanes=$lanes actual=$actual expected=$expected")
        }
      }
    }
  }

  @Test def pathologicalUnorderedAmbiguityHasAnExplicitComplexityDiagnostic(): Unit = {
    val lanes = Vector.fill(8)(Vector.fill(4)("same"))
    val golden = lanesGolden(lanes, continuation = Vector("after"))
    val error = expectAssertionError(verify(Vector.fill(32)("same") :+ "not-after", golden))

    Assert.assertTrue(error.getMessage.contains("MatcherComplexityExceeded"))
    Assert.assertTrue(error.getMessage.contains("100000-state budget"))
    Assert.assertTrue(error.getMessage.contains("No candidate state was discarded"))
    Assert.assertFalse(error.getMessage.contains("Exact transcript mismatch"))
  }

  @Test def trailingOutputAfterAnAmbiguousUnorderedBlockKeepsTheTrailingClassification(): Unit = {
    val golden = lanesGolden(Vector(Vector("same"), Vector("same")))
    val error = expectAssertionError(verify(Vector("same", "same", "trailing"), golden))

    Assert.assertTrue(error.getMessage.contains("Exact transcript has unexpected trailing output after line 2"))
    Assert.assertTrue(error.getMessage.contains("    3 | trailing"))
    Assert.assertFalse(error.getMessage.contains("Exact transcript mismatch"))
    Assert.assertFalse(error.getMessage.contains("MatcherComplexityExceeded"))
  }

  @Test def failedContinuationAfterAnAmbiguousUnorderedBlockReportsItsFurthestContext(): Unit = {
    val golden = lanesGolden(Vector(Vector("same"), Vector("same")), continuation = Vector("after"))
    val error = expectAssertionError(verify(Vector("same", "same", "wrong", "context-tail"), golden))

    Assert.assertTrue(error.getMessage.contains("Exact transcript mismatch"))
    Assert.assertTrue(error.getMessage.contains("at output line 3"))
    Assert.assertTrue(error.getMessage.contains("Expected golden line 9: after"))
    Assert.assertTrue(error.getMessage.contains("Actual: wrong"))
    Assert.assertTrue(error.getMessage.contains("    3 | wrong"))
    Assert.assertTrue(error.getMessage.contains("    4 | context-tail"))
    Assert.assertFalse(error.getMessage.contains("unexpected trailing output"))
    Assert.assertFalse(error.getMessage.contains("MatcherComplexityExceeded"))
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
      "[[/unordered]]",
      "after"
    )
    val coldBridge = Vector(
      "[info] Non-compiled module 'compiler-bridge_2.12' for Scala 2.12.20. Compiling...",
      "[info]   Compilation completed in 4.321s."
    )

    verify(Vector("other", "compile-started", "compile-finished", "after"), golden)
    verify(Vector("compile-started") ++ coldBridge ++ Vector("other", "compile-finished", "after"), golden)
    expectAssertionError(verify(Vector("compile-started", coldBridge.head, "other", "compile-finished", "after"), golden))
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
    val inExpectedOrder = javaSourcesMapping(Vector(
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloWorld.class",
      "com/jetbrains/sbt/test/HelloScala$.class"
    ))
    val reordered = javaSourcesMapping(Vector(
      "com/jetbrains/sbt/test/HelloWorld.class",
      "com/jetbrains/sbt/test/HelloScala$.class",
      "com/jetbrains/sbt/test/HelloScala.class"
    ))

    verify(Vector(packageMappingMessage(inExpectedOrder)), golden)
    verify(Vector(packageMappingMessage(reordered)), golden)
    verify(Vector(packageMappingMessage(scala3JavaSourcesMapping(Vector(
      "com/jetbrains/sbt/test/HelloScala.tasty",
      "com/jetbrains/sbt/test/HelloWorld.class",
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloScala$.class"
    )))), golden)
    expectAssertionError(verify(Vector(packageMappingMessage(javaSourcesMapping(Vector(
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloWorld.class"
    )))), golden))
    expectAssertionError(verify(Vector(packageMappingMessage(javaSourcesMapping(Vector(
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloWorld.class",
      "com/jetbrains/sbt/test/Unexpected.class"
    )))), golden))
    expectAssertionError(verify(Vector(packageMappingMessage(javaSourcesMapping(Vector(
      "HelloScala.class", "HelloScala$.class", "HelloWorld.class"
    )))), golden))

    val candidate = FileUtils.createTempFile("input-mappings-candidate", ".txt")
    SbtOutputVerifier.writeCandidate(Vector(packageMappingMessage(reordered)), candidate, context)
    Assert.assertTrue(FileUtils.read(candidate).contains("text='{{input-file-mappings:java-sources}}'"))
  }

  @Test def javaSourcesInputMappingsRejectUnexpectedClassesRoot(): Unit = {
    val golden = goldenFile(
      "##teamcity[message status='NORMAL' flowId='{{build-id:root}}:compile:general:packageBin' text='{{input-file-mappings:java-sources}}']"
    )
    val mapping = javaSourcesMapping(
      JavaSourceClassFiles,
      classesDirectory = "/tmp/unrelated/classes/"
    )

    expectAssertionError(verify(Vector(packageMappingMessage(mapping)), golden))
  }

  @Test def javaVersionPlaceholdersAcceptOnlyTheSelectedMajorAndRenderCandidates(): Unit = {
    val golden = goldenFile("{{java-version:8}}", "{{java-version:17}}")
    val actual = Vector("1.8.0_462", "17.0.16")
    val candidate = FileUtils.createTempFile("java-version-candidate", ".txt")

    verify(actual, golden)
    expectAssertionError(verify(Vector("1.8.0_462", "21.0.8"), golden))
    expectIllegalArgument(verify(actual, goldenFile("{{java-version:21}}")))

    SbtOutputVerifier.writeCandidate(actual, candidate, context)
    Assert.assertEquals(Vector("{{java-version:8}}", "{{java-version:17}}"), FileUtils.readLines(candidate).toVector)
  }

  @Test def candidateRenderingNormalisesOnlyBackgroundJobStagingHashes(): Unit = {
    val actual = Vector(
      "[debug] \t/repo/target/integration-tests/work/profile/scenario/target/bg-jobs/sbt_cafebabe/job-1/target/1234abcd/5678efab/product.jar",
      "[debug] \t/repo/target/integration-tests/work/profile/scenario/target/bg-jobs/sbt_cafebabe/target/8765dcba/80cde419/dependency.jar",
      "[debug] \t/repo/target/1234abcd/must-stay-literal.jar"
    )
    val destination = FileUtils.createTempFile("background-job-candidate", ".txt")

    SbtOutputVerifier.writeCandidate(actual, destination, context)

    val rendered = FileUtils.readLines(destination).toVector
    Assert.assertEquals(
      "[debug] \t{{path:work-dir}}/target/bg-jobs/sbt_{{hash:bg-job}}/job-1/target/{{hash:bg-job-target}}/{{hash:bg-job-content}}/product.jar",
      rendered.head
    )
    Assert.assertEquals(
      "[debug] \t{{path:work-dir}}/target/bg-jobs/sbt_{{hash:bg-job}}/target/{{hash:bg-job-target}}/80cde419/dependency.jar",
      rendered(1)
    )
    Assert.assertEquals("[debug] \t{{path:repo-root}}/target/1234abcd/must-stay-literal.jar", rendered(2))
    verify(actual, destination)
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

  @Test def candidateRenderingPreservesLiteralTabsInStackTraces(): Unit = {
    val actual = Vector(
      "##teamcity[testFailed name='fixture.Test.fails' details='java.lang.AssertionError: boom|n\tat org.junit.Assert.fail(Assert.java:89)|n\tat fixture.Test.fails(Test.scala:7)|n\tat java.base/java.lang.reflect.Method.invoke(Method.java:569)' flowId='17']"
    )
    val candidate = FileUtils.createTempFile("tab-stack-candidate", ".txt")

    SbtOutputVerifier.writeCandidate(actual, candidate, context)

    val rendered = FileUtils.read(candidate)
    Assert.assertTrue(rendered.contains("|n\tat org.junit.Assert.fail"))
    Assert.assertFalse(rendered.contains("|n\\tat org.junit.Assert.fail"))
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

  private def lanesGolden(lanes: Vector[Vector[String]], continuation: Vector[String] = Vector.empty): File = {
    val lines =
      Vector("[[unordered]]") ++
        lanes.zipWithIndex.flatMap { case (lane, index) =>
          Vector(s"[[lane:lane-$index]]") ++ lane ++ Vector("[[/lane]]")
        } ++
        Vector("[[/unordered]]") ++
        continuation
    goldenFile(lines*)
  }

  private def interleavings(left: Vector[String], right: Vector[String]): Vector[Vector[String]] =
    if (left.isEmpty) Vector(right)
    else if (right.isEmpty) Vector(left)
    else interleavings(left.tail, right).map(left.head +: _) ++ interleavings(left, right.tail).map(right.head +: _)

  private def laneInterleavings(lanes: Vector[Vector[String]]): Vector[Vector[String]] = {
    def loop(positions: Vector[Int]): Vector[Vector[String]] =
      if (positions.indices.forall(lane => positions(lane) == lanes(lane).size)) Vector(Vector.empty)
      else lanes.indices.flatMap { lane =>
        val position = positions(lane)
        if (position >= lanes(lane).size) Vector.empty
        else loop(positions.updated(lane, position + 1)).map(lanes(lane)(position) +: _)
      }.toVector

    loop(Vector.fill(lanes.size)(0))
  }

  private def bruteForceMatches(lanes: Vector[Vector[String]], actual: Vector[String]): Boolean = {
    def loop(index: Int, positions: Vector[Int]): Boolean =
      if (index == actual.size) positions.indices.forall(lane => positions(lane) == lanes(lane).size)
      else lanes.indices.exists { lane =>
        val position = positions(lane)
        position < lanes(lane).size && lanes(lane)(position) == actual(index) &&
          loop(index + 1, positions.updated(lane, position + 1))
      }

    loop(0, Vector.fill(lanes.size)(0))
  }

  private def sequences(alphabet: Vector[String], length: Int): Vector[Vector[String]] =
    if (length == 0) Vector(Vector.empty)
    else sequences(alphabet, length - 1).flatMap(prefix => alphabet.map(prefix :+ _))

  private def verifierAccepts(actual: Vector[String], golden: File): Boolean =
    try {
      verify(actual, golden)
      true
    } catch {
      case _: AssertionError => false
    }

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

  private def javaSourcesMapping(
    classFiles: Vector[String],
    classesDirectory: String = s"${context.paths("work-dir")}/target/scala-2.13/classes/"
  ): String = {
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
