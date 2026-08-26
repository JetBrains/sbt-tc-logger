package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtClassicScalaTestSemanticContractTest {
  import ObservedServiceMessageKind.*
  import PlainOutputContract.*
  import PlainOutputPattern.*
  import ProcessResultContract.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private val AllProfiles = Vector("sbt-1.4-jdk8", "sbt-1-jdk8", "sbt-1-jdk17", "sbt-2-jdk17")
  private val Jdk17Profiles = Vector("sbt-1-jdk17", "sbt-2-jdk17")
  private val FutureProfile = "sbt-3-jdk21"
  private val TaskSummary = "[success] Total time: 1 s"

  private val CommonSelections = Vector(
    "scalatest-pass-and-failure" -> SbtClassicScalaTestSemanticContracts.PassAndFailure,
    "scalatest-nested-suites" -> SbtClassicScalaTestSemanticContracts.NestedSuites,
    "scalatest-long-names" -> SbtClassicScalaTestSemanticContracts.LongNames
  )

  @Test def knownProfilesSelectSemanticOrHybridAndFutureProfilesRemainExact(): Unit = {
    CommonSelections.foreach { case (scenarioId, selection) =>
      AllProfiles.foreach(profile => Assert.assertTrue(
        s"Expected semantic $scenarioId on $profile",
        selection.forRuntimeProfile(profile).isInstanceOf[Semantic]
      ))
      Assert.assertEquals(ExactTranscript, selection.forRuntimeProfile(FutureProfile))
    }

    Jdk17Profiles.foreach(profile => Assert.assertTrue(
      s"Expected hybrid error-like mode on $profile",
      SbtClassicScalaTestSemanticContracts.ErrorLikeOutput
        .forRuntimeProfile(profile).isInstanceOf[Hybrid]
    ))
    Vector("sbt-1.4-jdk8", "sbt-1-jdk8", FutureProfile).foreach(profile =>
      Assert.assertEquals(ExactTranscript,
        SbtClassicScalaTestSemanticContracts.ErrorLikeOutput.forRuntimeProfile(profile)))

    Jdk17Profiles.foreach(profile => Assert.assertTrue(
      s"Expected semantic initializer mode on $profile",
      SbtClassicScalaTestSemanticContracts.InitializerError
        .forRuntimeProfile(profile).isInstanceOf[Semantic]
    ))
    Vector("sbt-1.4-jdk8", "sbt-1-jdk8", FutureProfile).foreach(profile =>
      Assert.assertEquals(ExactTranscript,
        SbtClassicScalaTestSemanticContracts.InitializerError.forRuntimeProfile(profile)))
  }

  @Test def everySupportedCoordinateAcceptsItsExactSemanticShape(): Unit = {
    CommonSelections.foreach { case (scenarioId, selection) =>
      AllProfiles.foreach { profile =>
        verify(validTranscript(scenarioId, profile), selection.forRuntimeProfile(profile))
      }
    }
    Jdk17Profiles.foreach { profile =>
      verify(errorLikeTranscript(profile),
        SbtClassicScalaTestSemanticContracts.ErrorLikeOutput.forRuntimeProfile(profile))
      verify(initializerTranscript(profile),
        SbtClassicScalaTestSemanticContracts.InitializerError.forRuntimeProfile(profile), exitCode = 1)
    }

    assertShape("scalatest-pass-and-failure", "sbt-1-jdk17", events = 16, lifecycles = 7)
    assertShape("scalatest-nested-suites", "sbt-1-jdk17", events = 48, lifecycles = 17)
    assertShape("scalatest-long-names", "sbt-1-jdk17", events = 11, lifecycles = 4)
    assertShape("scalatest-long-names", "sbt-2-jdk17", events = 6, lifecycles = 3)
    assertShape("scalatest-error-like-output", "sbt-1-jdk17", events = 30, lifecycles = 13)
    assertShape("initializer-error-suite-construction", "sbt-1-jdk17", events = 3, lifecycles = 0)
  }

  @Test def passAndFailureAcceptsEitherIndependentSuiteOrderButKeepsLocalSemanticsStrict(): Unit = {
    AllProfiles.foreach { profile =>
      val mode = SbtClassicScalaTestSemanticContracts.PassAndFailure.forRuntimeProfile(profile)
      Vector(false, true).foreach { exampleFirst =>
        verify(passTranscript(profile, exampleFirst), mode, exitCode = 1)
      }
    }

    val profile = "sbt-1-jdk17"
    val mode = SbtClassicScalaTestSemanticContracts.PassAndFailure.forRuntimeProfile(profile)
    val valid = passTranscript(profile)
    val wrongPrefix = updateUnique(valid, _.startsWith("##teamcity[testFailed"), "test failure",
      _.replace("true was not false", "changed failure"))
    val wrongUserFrame = updateUnique(valid, _.startsWith("##teamcity[testFailed"), "test failure",
      _.replace("ListFlatSpec.scala:14", "ListFlatSpec.scala:15"))
    val wrongOwnership = updateUnique(valid,
      _.startsWith("##teamcity[testSuiteStarted name='ListFlatSpec'"), "list suite start",
      _.replace("flow-list", "flow-example"))
    val taskBeforeSuites = valid.last +: valid.dropRight(1)

    Vector(wrongPrefix, wrongUserFrame).foreach(lines =>
      assertViolation(collect(lines, mode, exitCode = 1), SemanticCardinalityFailure))
    assertViolation(collect(wrongOwnership, mode, exitCode = 1), FlowOwnershipFailure)
    assertViolation(collect(taskBeforeSuites, mode, exitCode = 1), OrderingFailure)
  }

  @Test def nestedSuitesKeepFixtureSortedGroupsExactWhileCompilerBridgeRemainsOptional(): Unit = {
    AllProfiles.foreach { profile =>
      val mode = SbtClassicScalaTestSemanticContracts.NestedSuites.forRuntimeProfile(profile)
      verify(nestedTranscript(profile), mode)
      verify(nestedTranscript(profile, includeBridge = true), mode)
    }

    val profile = "sbt-1-jdk17"
    val mode = SbtClassicScalaTestSemanticContracts.NestedSuites.forRuntimeProfile(profile)
    val valid = nestedTranscript(profile)
    val wrongOutput = updateUnique(valid,
      _.contains("text='|[info|] - A should have ASCII value 41 hex'"), "aggregate A task-output line",
      _.replace("A should have ASCII value 41 hex", "A should have ASCII value 42 hex"))
    val wrongOutputOwner = updateUnique(valid,
      _.contains("text='|[info|] - C should have ASCII value 43 hex'"), "aggregate C task-output line",
      _.replace("100:test:general:test", "101:test:general:test"))
    val aStart = uniqueIndex(valid, _.startsWith("##teamcity[testSuiteStarted name='ASuite'"), "ASuite start")
    val aFinish = uniqueIndex(valid, _.startsWith("##teamcity[testSuiteFinished name='ASuite'"), "ASuite finish")
    val bStart = uniqueIndex(valid, _.startsWith("##teamcity[testSuiteStarted name='BSuite'"), "BSuite start")
    val bFinish = uniqueIndex(valid, _.startsWith("##teamcity[testSuiteFinished name='BSuite'"), "BSuite finish")
    require(aFinish + 1 == bStart, "Expected adjacent ASuite and BSuite blocks in the fixture-defined transcript.")
    val reversedGroups = valid.take(aStart) ++
      valid.slice(bStart, bFinish + 1) ++
      valid.slice(aStart, aFinish + 1) ++
      valid.drop(bFinish + 1)

    assertViolation(collect(wrongOutput, mode), SemanticCardinalityFailure)
    assertViolation(collect(wrongOutputOwner, mode), FlowOwnershipFailure)
    val reversedFindings = collect(reversedGroups, mode)
    assertViolation(reversedFindings, OrderingFailure)
    Vector(SemanticCardinalityFailure, FlowOwnershipFailure, LifecycleFailure).foreach { category =>
      Assert.assertFalse(
        s"A complete suite-block reorder must not produce $category: ${describe(reversedFindings)}",
        reversedFindings.exists(finding => finding.category == category && finding.disposition == Violation)
      )
    }

    val bridged = nestedTranscript(profile, includeBridge = true)
    val completion = uniqueIndex(bridged, _.contains("Compilation completed in"), "bridge completion")
    assertViolation(collect(bridged.patch(completion, Vector.empty, 1), mode), SemanticCardinalityFailure)
  }

  @Test def longNamesRemainUnduplicatedAndKeepTheirProfileSpecificCompilationShape(): Unit = {
    AllProfiles.foreach { profile =>
      val mode = SbtClassicScalaTestSemanticContracts.LongNames.forRuntimeProfile(profile)
      val valid = longNamesTranscript(profile)
      verify(valid, mode)
      Assert.assertEquals(2, valid.count(_.startsWith("##teamcity[testStarted")))
      Assert.assertEquals(2, valid.count(_.startsWith("##teamcity[testFinished")))
      Assert.assertEquals(!isSbt2(profile), valid.exists(_.startsWith("##teamcity[compilationStarted")))
    }

    val sbt1Mode = SbtClassicScalaTestSemanticContracts.LongNames.forRuntimeProfile("sbt-1-jdk17")
    val sbt1 = longNamesTranscript("sbt-1-jdk17")
    val duplicatedName = updateUnique(sbt1,
      line => line.startsWith("##teamcity[testStarted") && line.contains("XYZ-1"), "first long test",
      line => line.replace("Feature: Some Advanced Mode Scenario: ",
        "Feature: Some Advanced Mode Feature: Some Advanced Mode Scenario: "))
    val missingWarning = sbt1.filterNot(_.contains("3 deprecations"))
    Vector(duplicatedName, missingWarning).foreach(lines =>
      assertViolation(collect(lines, sbt1Mode), SemanticCardinalityFailure))

    val sbt2Mode = SbtClassicScalaTestSemanticContracts.LongNames.forRuntimeProfile("sbt-2-jdk17")
    val inventedCompilation = compilationBoundary(
      "compilationStarted",
      "Scala compiler in Test [scalatest-long-names]",
      "100:test:compiler"
    ) +: longNamesTranscript("sbt-2-jdk17")
    assertViolation(collect(inventedCompilation, sbt2Mode), SemanticCardinalityFailure)
  }

  @Test def errorLikeHybridRequiresEveryPlainLinePlacementAndOneSharedLogbackThread(): Unit = {
    Jdk17Profiles.foreach { profile =>
      val mode = SbtClassicScalaTestSemanticContracts.ErrorLikeOutput.forRuntimeProfile(profile)
      val valid = errorLikeTranscript(profile)
      verify(valid, mode)
      Assert.assertEquals(14, valid.count(line => !line.startsWith("##teamcity[")))

      val missingPlain = valid.patch(firstIndex(valid, _.startsWith("WARNING: Invalid stat"),
        "first stat warning"), Vector.empty, 1)
      val wrongErrorText = updateFirst(valid,
        line => line.contains("ERROR TestSpec") && line.contains("some error in test output"),
        "first Logback error",
        _.replace("some error in test output", "changed output"))
      val secondWarnIndex = valid.indices.filter(index =>
        valid(index).contains("WARN") && valid(index).contains("Invalid blah-blah-blah")
      ).toVector.last
      val splitThread = valid.updated(secondWarnIndex,
        valid(secondWarnIndex).replace("pool-4-thread-2", "pool-9-thread-1"))
      val firstTest = firstIndex(valid,
        _.startsWith("##teamcity[testStarted name='TestSpec.Some Test stat warnings'"),
        "first TestSpec test start")
      val firstPlain = firstIndex(valid, _.startsWith("WARNING: Invalid stat"), "first stat warning")
      val misplaced = moveLine(valid, firstPlain, firstTest + 1)
      val reusedFlow = valid.map(_.replace("flow-test-spec-2", "flow-test-spec-1"))
      val childOnOtherInvocation = updateUnique(valid,
        line => line.startsWith("##teamcity[testStarted name='TestSpec.Some Test stat warnings'") &&
          line.contains("flowId='flow-test-spec-2'"),
        "second invocation first child start",
        _.replace("flow-test-spec-2", "flow-test-spec-1"))

      Vector(missingPlain, wrongErrorText, splitThread, misplaced).foreach(lines =>
        assertViolation(collect(lines, mode), PlainOutputFailure))
      Vector(reusedFlow, childOnOtherInvocation).foreach(lines =>
        assertViolation(collect(lines, mode), FlowOwnershipFailure))
    }

    val profile = "sbt-2-jdk17"
    val mode = SbtClassicScalaTestSemanticContracts.ErrorLikeOutput.forRuntimeProfile(profile)
    val wrongInspection = updateUnique(errorLikeTranscript(profile),
      _.startsWith("##teamcity[inspection SEVERITY='WARNING'"), "compiler inspection",
      _.replace("A pure expression", "a pure expression"))
    assertViolation(collect(wrongInspection, mode), SemanticCardinalityFailure)
  }

  @Test def initializerFailureAcceptsOnlyTheExpectedUnfinishedSuiteAndStructuredCause(): Unit = {
    Jdk17Profiles.foreach { profile =>
      val mode = SbtClassicScalaTestSemanticContracts.InitializerError.forRuntimeProfile(profile)
      val valid = initializerTranscript(profile)
      verify(valid, mode, exitCode = 1)
      Assert.assertEquals(1, valid.count(_.startsWith("##teamcity[testSuiteStarted")))
      Assert.assertEquals(0, valid.count(_.startsWith("##teamcity[testSuiteFinished")))
      Assert.assertEquals(0, valid.count(_.startsWith("##teamcity[testStarted")))

      val completedSuite = valid :+ suiteBoundary("testSuiteFinished", "InitializerErrorSuite", "initializer-flow")
      val wrongCause = updateUnique(valid, _.contains("ExceptionInInitializerError|n"), "initializer details",
        _.replace("Suite construction failure for #12", "changed construction failure"))
      val wrongFixtureFrame = updateUnique(valid, _.contains("ExceptionInInitializerError|n"), "initializer details",
        _.replace("InitializerErrorSuite.scala:4", "InitializerErrorSuite.scala:5"))
      val foreignTail = updateUnique(valid, _.contains("ExceptionInInitializerError|n"), "initializer details",
        _.replace("org.scalatest.tools.Framework$ScalaTestTask", "com.foreign.Runner"))

      Vector(completedSuite, wrongCause, wrongFixtureFrame, foreignTail).foreach(lines =>
        assertViolation(collect(lines, mode, exitCode = 1), SemanticCardinalityFailure))
    }
  }

  @Test def compoundMutationAggregatesIndependentFailuresAndBlocksMissingEdges(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = SbtClassicScalaTestSemanticContracts.PassAndFailure.forRuntimeProfile(profile)
    val valid = passTranscript(profile)
    val start = uniqueIndex(valid,
      _.startsWith("##teamcity[testStarted name='ExampleSpec.A Stack should pop"), "example first test start")
    val finish = uniqueIndex(valid,
      _.startsWith("##teamcity[testFinished name='ExampleSpec.A Stack should pop"), "example first test finish")
    val wrongOrder = valid.updated(start, valid(finish)).updated(finish, valid(start))
    val wrongOwnership = updateUnique(wrongOrder,
      _.startsWith("##teamcity[testSuiteStarted name='ListFlatSpec'"), "list suite start",
      _.replace("flow-list", "flow-example"))
    val wrongLifecycle = updateUnique(wrongOwnership,
      _.startsWith("##teamcity[testSuiteFinished name='ExampleSpec'"), "example suite finish",
      _.replace("ExampleSpec", "BrokenExampleSpec"))
    val mutated = wrongLifecycle ++ Vector(
      "##teamcity[fixtureUnexpected value='extra']",
      "unexpected plain output"
    )
    val contract = effectiveContract(mode).copy(processResult = Success)
    val failure = expectFailure(SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 7))
    val findings = failure.failures

    Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach { category =>
      assertViolation(findings, category)
      Assert.assertTrue(failure.getMessage, failure.getMessage.contains(category.toString))
    }
    assertBlocked(findings, OrderingFailure, "edge:example-spec-finish->test-task-failure")
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Violation]"))
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Blocked]"))
    Assert.assertFalse(describe(findings), findings.exists(_.category == MatcherComplexityFailure))
  }

  private def validTranscript(scenarioId: String, profile: String): Vector[String] = scenarioId match {
    case "scalatest-pass-and-failure" => passTranscript(profile)
    case "scalatest-nested-suites" => nestedTranscript(profile)
    case "scalatest-long-names" => longNamesTranscript(profile)
  }

  private def passTranscript(profile: String, exampleFirst: Boolean = false): Vector[String] = {
    val listTests = Vector(
      passingTest("ListFlatSpec.A List should have length as count of elements in it", "flow-list"),
      failedTest(
        "ListFlatSpec.A List should contains elements passed in the factory method",
        "flow-list",
        passFailureDetails(profile)
      ),
      passingTest(
        "ListFlatSpec.A List should throw IndexOutOfBounds exception when index is out of bounds",
        "flow-list"
      )
    ).flatten
    val exampleTests = Vector(
      passingTest("ExampleSpec.A Stack should pop values in last-in-first-out order", "flow-example"),
      passingTest(
        "ExampleSpec.A Stack should throw NoSuchElementException if an empty stack is popped",
        "flow-example"
      )
    ).flatten
    val list = suiteLines("ListFlatSpec", "flow-list", listTests)
    val example = suiteLines("ExampleSpec", "flow-example", exampleTests)
    val suites = if (exampleFirst) example ++ list else list ++ example
    suites ++ Vector(
      message("100:test:general:" + testTask(profile), "ERROR",
        s"[error] (Test / ${testTask(profile)}) sbt.TestsFailedException: Tests unsuccessful")
    ) ++ taskSummaries(profile, 1)
  }

  private def passFailureDetails(profile: String): String = {
    val (prefix, userFrame) = if (isSbt2(profile)) (
      "org.scalatest.exceptions.TestFailedException: true was not equal to false",
      "\tat ListFlatSpec.$anonfun$new$2(ListFlatSpec.scala:13)"
    ) else (
      "org.scalatest.exceptions.TestFailedException: true was not false",
      "\tat ListFlatSpec.$anonfun$new$2(ListFlatSpec.scala:14)"
    )
    prefix +
      "\n\tat org.scalatest.matchers.MatchersHelper$.indicateFailure(MatchersHelper.scala:392)" +
      s"\n$userFrame" +
      "\n\tat org.scalatest.Suite.run(Suite.scala:1114)"
  }

  private final case class AsciiSuite(name: String, flow: String, tests: Vector[String], output: Vector[String])

  private val AsciiSuites = Vector(
    AsciiSuite("ASCIISuite", "flow-ascii", Vector(
      "ASCIISuite.ASuite.A should have ASCII value 41 hex",
      "ASCIISuite.ASuite.a should have ASCII value 61 hex",
      "ASCIISuite.BSuite.B should have ASCII value 42 hex",
      "ASCIISuite.BSuite.b should have ASCII value 62 hex",
      "ASCIISuite.CSuite.C should have ASCII value 43 hex",
      "ASCIISuite.CSuite.c should have ASCII value 63 hex"
    ), Vector(
      "ASuite:", "- A should have ASCII value 41 hex", "- a should have ASCII value 61 hex",
      "BSuite:", "- B should have ASCII value 42 hex", "- b should have ASCII value 62 hex",
      "CSuite:", "- C should have ASCII value 43 hex", "- c should have ASCII value 63 hex"
    )),
    AsciiSuite("ASuite", "flow-a", Vector(
      "ASuite.A should have ASCII value 41 hex", "ASuite.a should have ASCII value 61 hex"
    ), Vector("ASuite:")),
    AsciiSuite("BSuite", "flow-b", Vector(
      "BSuite.B should have ASCII value 42 hex", "BSuite.b should have ASCII value 62 hex"
    ), Vector("BSuite:")),
    AsciiSuite("CSuite", "flow-c", Vector(
      "CSuite.C should have ASCII value 43 hex", "CSuite.c should have ASCII value 63 hex"
    ), Vector("CSuite:"))
  )

  private def nestedTranscript(profile: String, includeBridge: Boolean = false): Vector[String] = {
    val outputSuffix = if (isSbt2(profile))
      "/target/out/jvm/scala-3.8.4/scalatest-nested-suites/test-classes ..."
    else "/target/scala-2.13/test-classes ..."
    val compilation = Vector(
      compilationBoundary("compilationStarted", "Scala compiler in Test [scalatest-nested-suites]", "100:test:compiler"),
      message("100:test:compiler", "NORMAL", s"[info] compiling 1 Scala source to /work$outputSuffix")
    ) ++ Option.when(includeBridge)(compilerBridge("100:test:compiler")).getOrElse(Vector.empty) ++ Vector(
      message("100:test:compiler", "NORMAL", "[info] done compiling"),
      compilationBoundary("compilationFinished", "Scala compiler in Test [scalatest-nested-suites]", "100:test:compiler")
    )
    val suites = AsciiSuites.flatMap { suite =>
      Vector(suiteBoundary("testSuiteStarted", suite.name, suite.flow)) ++
        suite.output.map(text => message(s"100:test:general:${testTask(profile)}", "NORMAL", s"[info] $text")) ++
        suite.tests.flatMap(passingTest(_, suite.flow)) ++
        Vector(suiteBoundary("testSuiteFinished", suite.name, suite.flow))
    }
    compilation ++ suites ++ taskSummaries(profile, 1)
  }

  private val LongSuite = "com.jetbrains.teamcity.specs.some_name.some_group.MyTestModeSpec"
  private val LongTest1 = LongSuite +
    ".Feature: Some Advanced Mode Scenario: XYZ-1: Lorem ipsum dolor sit amet, consectetur adipiscing elit, " +
    "sed do eiusmod tempor incididunt ut labore et dolore magna aliqua"
  private val LongTest2 = LongSuite +
    ".Feature: Some Advanced Mode Scenario: XYZ-2: Lorem Ipsum is simply dummy text of the printing and " +
    "typesetting industry."

  private def longNamesTranscript(profile: String): Vector[String] = {
    val compilation = if (isSbt2(profile)) Vector.empty else Vector(
      compilationBoundary("compilationStarted", "Scala compiler in Test [scalatest-long-names]", "100:test:compiler"),
      inspectionType,
      message("100:test:compiler", "WARNING",
        "[warn] 3 deprecations (since 3.1.0); re-run with -deprecation for details"),
      message("100:test:compiler", "WARNING", "[warn] one warning found"),
      compilationBoundary("compilationFinished", "Scala compiler in Test [scalatest-long-names]", "100:test:compiler")
    )
    compilation ++ suiteLines(LongSuite, "flow-long",
      passingTest(LongTest1, "flow-long") ++ passingTest(LongTest2, "flow-long"))
  }

  private def errorLikeTranscript(profile: String): Vector[String] = {
    val problem = if (isSbt2(profile))
      "A pure expression does nothing in statement position"
    else "a pure expression does nothing in statement position"
    val path = "/work/src/test/scala/TestSpec.scala"
    val compilation = Vector(
      compilationBoundary("compilationStarted", "Scala compiler in Test [scalatest-error-like-output]", "100:test:compiler"),
      inspectionType,
      service("inspection",
        "SEVERITY" -> "WARNING", "line" -> "11", "typeId" -> "SbtCompileProblem",
        "message" -> problem, "file" -> path),
      message("100:test:compiler", "WARNING",
        s"[warn] $path:11: $problem\n" +
          "[warn]     \"Hello\"  //warning will be risen here\n" +
          "[warn]     ^"),
      message("100:test:compiler", "WARNING", "[warn] one warning found"),
      compilationBoundary("compilationFinished", "Scala compiler in Test [scalatest-error-like-output]", "100:test:compiler")
    )
    val testNames = Vector(
      "Some Test stat warnings",
      "Some Test should print warnings",
      "Some Test run something",
      "Some Test log warning",
      "Some Test log error"
    )
    val suites = Vector(1, 2).flatMap { invocation =>
      val flow = s"flow-test-spec-$invocation"
      Vector(suiteBoundary("testSuiteStarted", "TestSpec", flow)) ++
        errorLikePlainLines(profile) ++
        testNames.flatMap(name => passingTest(s"TestSpec.$name", flow)) ++
        Vector(suiteBoundary("testSuiteFinished", "TestSpec", flow))
    }
    compilation ++ suites
  }

  private def errorLikePlainLines(profile: String): Vector[String] = {
    val warn = if (isSbt2(profile))
      "12:34:56.789 [pool-4-thread-2-ScalaTest-running-TestSpec] WARN  TestSpec - WARNING: Invalid blah-blah-blah"
    else
      "12:34:56.789 [pool-4-thread-2-ScalaTest-running-TestSpec] WARN TestSpec -- WARNING: Invalid blah-blah-blah"
    val error = if (isSbt2(profile))
      "12:34:56.790 [pool-4-thread-2-ScalaTest-running-TestSpec] ERROR TestSpec - [error] some error in test output"
    else
      "12:34:56.790 [pool-4-thread-2-ScalaTest-running-TestSpec] ERROR TestSpec -- [error] some error in test output"
    Vector(
      "WARNING: Invalid stat name /127.0.0.1:4010_backoffs exported as _127_0_0_1_4010_backoffs",
      "WARNING 3/19/14 1:26 PM:liquidbase: modifyDataType will lose primary key/autoincrement/not null settings for mysql",
      "Invalid stat name /127...",
      "- About to log waring!",
      warn,
      "- About to log error!",
      error
    )
  }

  private def initializerTranscript(profile: String): Vector[String] = {
    val task = if (isSbt2(profile)) "testQuick" else "executeTests"
    val top = Vector(
      "[error] java.lang.ExceptionInInitializerError",
      "[error] \tat InitializerErrorSuite.<init>(InitializerErrorSuite.scala:4)",
      "[error] \tat java.base/jdk.internal.reflect.NativeConstructorAccessorImpl.newInstance0(Native Method)",
      "[error] \tat org.scalatest.tools.Framework$ScalaTestTask.execute(Framework.scala:454)",
      "[error] Caused by: java.lang.IllegalStateException: Suite construction failure for #12"
    )
    val cause = if (isSbt2(profile)) Vector(
      "[error] \tat InitializerErrorFixture$.<init>(InitializerErrorSuite.scala:12)",
      "[error] \tat InitializerErrorFixture$.<clinit>(InitializerErrorSuite.scala)",
      "[error] \tat InitializerErrorSuite.<init>(InitializerErrorSuite.scala:4)"
    ) else Vector(
      "[error] \tat InitializerErrorFixture$.<clinit>(InitializerErrorSuite.scala:12)",
      "[error] \tat InitializerErrorSuite.<init>(InitializerErrorSuite.scala:4)"
    )
    Vector(
      suiteBoundary("testSuiteStarted", "InitializerErrorSuite", "initializer-flow"),
      message(s"100:test:general:$task", "ERROR", (top ++ cause ++ Vector(
        "[error] \tat java.base/java.lang.reflect.Constructor.newInstanceWithCaller(Constructor.java:500)",
        "[error] \tat org.scalatest.tools.Framework$ScalaTestTask.execute(Framework.scala:454)"
      )).mkString("\n")),
      message(s"100:test:general:$task", "ERROR", s"[error] (Test / $task) java.lang.ExceptionInInitializerError")
    ) ++ taskSummaries(profile, 1)
  }

  private def passingTest(name: String, flow: String): Vector[String] = Vector(
    service("testStarted", "name" -> name, "captureStandardOutput" -> "true", "flowId" -> flow),
    service("testFinished", "name" -> name, "duration" -> "17.5", "flowId" -> flow)
  )

  private def failedTest(name: String, flow: String, details: String): Vector[String] = Vector(
    service("testStarted", "name" -> name, "captureStandardOutput" -> "true", "flowId" -> flow),
    service("testFailed", "name" -> name, "details" -> details, "flowId" -> flow),
    service("testFinished", "name" -> name, "duration" -> "17.5", "flowId" -> flow)
  )

  private def suiteLines(name: String, flow: String, tests: Vector[String]): Vector[String] =
    Vector(suiteBoundary("testSuiteStarted", name, flow)) ++ tests ++
      Vector(suiteBoundary("testSuiteFinished", name, flow))

  private def suiteBoundary(kind: String, name: String, flow: String): String =
    service(kind, "name" -> name, "flowId" -> flow)

  private def compilationBoundary(kind: String, compiler: String, flow: String): String =
    service(kind, "compiler" -> compiler, "flowId" -> flow)

  private def message(flow: String, status: String, text: String): String =
    service("message", "status" -> status, "flowId" -> flow, "text" -> text)

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

  private def inspectionType: String = service("inspectionType",
    "id" -> "SbtCompileProblem",
    "name" -> "sbt compile problem",
    "description" -> "Compile problems",
    "category" -> "Compile problems")

  private def compilerBridge(flow: String): Vector[String] = Vector(
    message(flow, "NORMAL", "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.18. Compiling..."),
    message(flow, "NORMAL", "[info]   Compilation completed in 1.25s.")
  )

  private def taskSummaries(profile: String, count: Int): Vector[String] =
    if (isSbt2(profile)) Vector.fill(count)(TaskSummary) else Vector.empty

  private def testTask(profile: String): String = if (isSbt2(profile)) "testQuick" else "test"
  private def isSbt2(profile: String): Boolean = profile == "sbt-2-jdk17"

  private def assertShape(
    scenarioId: String,
    profile: String,
    events: Int,
    lifecycles: Int
  ): Unit = {
    val contract = SbtClassicScalaTestSemanticContracts.semanticContractFor(scenarioId, profile)
    Assert.assertEquals(s"Events for $scenarioId/$profile", events, contract.events.map(_.multiplicity).sum)
    Assert.assertEquals(s"Lifecycles for $scenarioId/$profile", lifecycles, contract.lifecycles.size)
    Assert.assertEquals(DelegatedToHarness, contract.processResult)
  }

  private def effectiveContract(mode: SbtOutputVerification): SbtSemanticContract = mode match {
    case Semantic(contract) => contract
    case Hybrid(contract, plainOutput) => contract.copy(plainOutput = plainOutput)
    case other => throw new AssertionError(s"Expected semantic or hybrid mode, got $other")
  }

  private def verify(lines: Vector[String], mode: SbtOutputVerification, exitCode: Int = 0): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      effectiveContract(mode),
      exitCode,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(
    lines: Vector[String],
    mode: SbtOutputVerification,
    exitCode: Int = 0
  ): Vector[SbtSemanticFailure] = SbtSemanticOutputVerifier.collect(
    lines,
    effectiveContract(mode),
    exitCode,
    delegated = SbtDelegatedVerification(processResultVerified = true)
  )

  private def updateUnique(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String,
    update: String => String
  ): Vector[String] = {
    val index = uniqueIndex(lines, predicate, description)
    lines.updated(index, update(lines(index)))
  }

  private def updateFirst(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String,
    update: String => String
  ): Vector[String] = {
    val index = firstIndex(lines, predicate, description)
    lines.updated(index, update(lines(index)))
  }

  private def firstIndex(lines: Vector[String], predicate: String => Boolean, description: String): Int = {
    val index = lines.indexWhere(predicate)
    require(index >= 0, s"Expected at least one $description.")
    index
  }

  private def uniqueIndex(lines: Vector[String], predicate: String => Boolean, description: String): Int = {
    val indexes = lines.indices.filter(index => predicate(lines(index))).toVector
    require(indexes.size == 1, s"Expected one $description, found indexes $indexes.")
    indexes.head
  }

  private def moveLine(lines: Vector[String], from: Int, to: Int): Vector[String] = {
    val line = lines(from)
    val without = lines.patch(from, Vector.empty, 1)
    without.patch(math.min(to, without.size), Vector(line), 0)
  }

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory,
    identity: String = ""
  ): Unit = Assert.assertTrue(
    s"Expected $category Violation${Option.when(identity.nonEmpty)(s" for $identity").getOrElse("")} " +
      s"in ${describe(findings)}",
    findings.exists(finding =>
      finding.category == category && finding.disposition == Violation &&
        (identity.isEmpty || finding.semanticIdentity == identity)
    )
  )

  private def assertBlocked(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory,
    identity: String
  ): Unit = Assert.assertTrue(
    s"Expected $category Blocked for $identity in ${describe(findings)}",
    findings.exists(finding =>
      finding.category == category && finding.disposition == Blocked && finding.semanticIdentity == identity
    )
  )

  private def expectFailure(body: => Unit): SbtSemanticVerificationException = try {
    body
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def describe(findings: Vector[SbtSemanticFailure]): String =
    findings.map(finding => (finding.category, finding.disposition, finding.semanticIdentity)).mkString(", ")

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
