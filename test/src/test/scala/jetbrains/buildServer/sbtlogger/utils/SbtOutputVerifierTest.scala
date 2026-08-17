package jetbrains.buildServer.sbtlogger.utils

import org.jetbrains.sbt.integrationTests.FileUtils
import org.junit.{Assert, Test}

import java.io.File
import java.nio.file.Files

/**
 * The test doesn't test business logic.
 *
 * Instead, it tests the implementation of the test utility [[SbtOutputVerifierTest]]
 */
class SbtOutputVerifierTest {

  @Test
  def compilationLifecycleRequiresEveryLegacySummaryToBeOwnedByOneOrderedPair(): Unit =
    SbtOutputVerifier.assertCompilationLifecycle(
      """##teamcity[compilationStarted compiler='Scala compiler' flowId='main']
        |##teamcity[message status='ERROR' flowId='main' text='one error found']
        |##teamcity[compilationFinished compiler='Scala compiler' flowId='main']
        |""".stripMargin,
      SbtCompilationLifecycleExpectation.Complete(
        expectedClosures = 1,
        expectedLegacyErrorSummaries = Some(1)
      )
    )

  @Test
  def compilationLifecycleRejectsDuplicateOrUnmatchedFinishes(): Unit = {
    val duplicateFinish = expectAssertionError {
      SbtOutputVerifier.assertCompilationLifecycle(
        """##teamcity[compilationStarted compiler='Scala compiler' flowId='main']
          |##teamcity[compilationFinished compiler='Scala compiler' flowId='main']
          |##teamcity[compilationFinished compiler='Scala compiler' flowId='main']
          |""".stripMargin,
        SbtCompilationLifecycleExpectation.Complete(expectedClosures = 1)
      )
    }
    assertFailureMessageContains(duplicateFinish, "exactly one compilation finish")

    val unmatchedFinish = expectAssertionError {
      SbtOutputVerifier.assertCompilationLifecycle(
        "##teamcity[compilationFinished compiler='Scala compiler' flowId='main']\n",
        SbtCompilationLifecycleExpectation.Complete(expectedClosures = 1)
      )
    }
    assertFailureMessageContains(unmatchedFinish, "exactly one compilation start")
  }

  @Test
  def compilationLifecycleRejectsLegacySummaryOnAnotherFlow(): Unit = {
    val error = expectAssertionError {
      SbtOutputVerifier.assertCompilationLifecycle(
        """##teamcity[compilationStarted compiler='Scala compiler' flowId='main']
          |##teamcity[message status='ERROR' flowId='other' text='one error found']
          |##teamcity[compilationFinished compiler='Scala compiler' flowId='main']
          |""".stripMargin,
        SbtCompilationLifecycleExpectation.Complete(expectedClosures = 1, expectedLegacyErrorSummaries = Some(1))
      )
    }

    assertFailureMessageContains(error, "inside exactly one complete compilation lifecycle")
  }

  @Test
  def compilationLifecycleRejectsLegacySummaryAfterItsFinish(): Unit = {
    val error = expectAssertionError {
      SbtOutputVerifier.assertCompilationLifecycle(
        """##teamcity[compilationStarted compiler='Scala compiler' flowId='main']
          |##teamcity[compilationFinished compiler='Scala compiler' flowId='main']
          |##teamcity[message status='ERROR' flowId='main' text='one error found']
          |""".stripMargin,
        SbtCompilationLifecycleExpectation.Complete(expectedClosures = 1, expectedLegacyErrorSummaries = Some(1))
      )
    }

    assertFailureMessageContains(error, "inside exactly one complete compilation lifecycle")
  }

  @Test
  def checkOutputTextAcceptsRequiredPatternsInOrder(): Unit = {
    val required = patternFile("required", "first", "second")

    SbtOutputVerifier.checkOutputText(
      "first\nnoise\nsecond\n",
      excludesFile = None,
      requiredFiles = Seq(required)
    )
  }

  @Test
  def checkOutputTextRejectsForbiddenPatterns(): Unit = {
    val excludes = patternFile("excludes", "forbidden")

    expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        "allowed\nforbidden\n",
        excludesFile = Some(excludes),
        requiredFiles = Seq.empty
      )
    }
  }

  @Test
  def requiredPatternsConsumeAtMostOnePatternPerOutputLine(): Unit = {
    val required = patternFile("required", "first", "second")

    expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        "first second\n",
        excludesFile = None,
        requiredFiles = Seq(required)
      )
    }
  }

  @Test
  def flowIdPlaceholderRequiresRepeatedTokensToUseTheSameConcreteFlow(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testSuiteStarted name='suite' flowId='<flowId1>'\]""",
      """##teamcity\[testStarted name='suite.test' captureStandardOutput='true' flowId='<flowId1>'\]""",
      """##teamcity\[testFinished name='suite.test' duration='.*' flowId='<flowId1>'\]""",
      """##teamcity\[testSuiteFinished name='suite' flowId='<flowId1>'\]"""
    )

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testSuiteStarted name='suite' flowId='thread-A']
        |##teamcity[testStarted name='suite.test' captureStandardOutput='true' flowId='thread-A']
        |##teamcity[testFinished name='suite.test' duration='7' flowId='thread-A']
        |##teamcity[testSuiteFinished name='suite' flowId='thread-A']
        |""".stripMargin,
      excludesFile = None,
      requiredFiles = Seq(required)
    )
  }

  @Test
  def flowIdPlaceholderRejectsADifferentConcreteFlowForARepeatedToken(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testSuiteStarted name='suite' flowId='<flowId1>'\]""",
      """##teamcity\[testSuiteFinished name='suite' flowId='<flowId1>'\]"""
    )

    val error = expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        """##teamcity[testSuiteStarted name='suite' flowId='first-thread']
          |##teamcity[testSuiteFinished name='suite' flowId='second-thread']
          |""".stripMargin,
        excludesFile = None,
        requiredFiles = Seq(required)
      )
    }

    assertFailureMessageContains(error, "flowId1 = 'first-thread'")
    assertFailureMessageContains(error, "First missing pattern:")
  }

  @Test
  def flowIdPlaceholdersRequireDifferentTokensToUseDifferentConcreteFlows(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testSuiteStarted name='first' flowId='<flowId1>'\]""",
      """##teamcity\[testSuiteStarted name='second' flowId='<flowId2>'\]"""
    )

    val error = expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        """##teamcity[testSuiteStarted name='first' flowId='shared-thread']
          |##teamcity[testSuiteStarted name='second' flowId='shared-thread']
          |""".stripMargin,
        excludesFile = None,
        requiredFiles = Seq(required)
      )
    }

    assertFailureMessageContains(error, "flowId1 = 'shared-thread'")
    assertFailureMessageContains(error, "flowId2 cannot bind to 'shared-thread': it is already bound to flowId1")
  }

  @Test
  def flowIdPlaceholderBindingsAreScopedToOneRequiredFile(): Unit = {
    val firstRequired = patternFile("first-required", """##teamcity\[testSuiteStarted name='first' flowId='<flowId1>'\]""")
    val secondRequired = patternFile("second-required", """##teamcity\[testSuiteStarted name='second' flowId='<flowId1>'\]""")

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testSuiteStarted name='first' flowId='shared-thread']
        |##teamcity[testSuiteStarted name='second' flowId='shared-thread']
        |""".stripMargin,
      excludesFile = None,
      requiredFiles = Seq(firstRequired, secondRequired)
    )
  }

  @Test
  def flowIdPlaceholderSearchBacktracksToALaterCompatibleOrderedSubsequence(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testStarted name='suite.test' captureStandardOutput='true' flowId='<flowId7>'\]""",
      """##teamcity\[testFinished name='suite.test' duration='.*' flowId='<flowId7>'\]"""
    )

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testStarted name='suite.test' captureStandardOutput='true' flowId='first-thread']
        |##teamcity[testStarted name='suite.test' captureStandardOutput='true' flowId='second-thread']
        |##teamcity[testFinished name='suite.test' duration='1' flowId='second-thread']
        |""".stripMargin,
      excludesFile = None,
      requiredFiles = Seq(required)
    )
  }

  @Test
  def flowIdPlaceholderRecognisesAServiceMessageWithTrailingConsoleOutput(): Unit = {
    val required = patternFile(
      "required",
      """##teamcity\[testStarted name='suite.test' captureStandardOutput='true'.* flowId='<flowId1>'\]""",
      """##teamcity\[testFinished name='suite.test'.* flowId='<flowId1>'\]"""
    )

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testStarted name='suite.test' captureStandardOutput='true' flowId='worker-7'][info] suite started
        |##teamcity[testFinished name='suite.test' duration='4' flowId='worker-7'][info] suite finished
        |""".stripMargin,
      excludesFile = None,
      requiredFiles = Seq(required)
    )
  }

  @Test
  def requiredPatternFailureReportsUsefulMatchContext(): Unit = {
    val required = patternFile("required", "first", "second")

    val error = expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        "first\nunexpected\n",
        excludesFile = None,
        requiredFiles = Seq(required)
      )
    }

    assertFailureMessageContains(error, "Matched 1/2 patterns across 2 captured output lines.")
    assertFailureMessageContains(error, "Last matched pattern:")
    assertFailureMessageContains(error, "matched output line 1: first")
    assertFailureMessageContains(error, "First missing pattern:")
    assertFailureMessageContains(error, "See the build log for the complete nested-sbt output.")
  }

  @Test
  def expectationValidationRejectsAlphaEquivalentDuplicateGroups(): Unit = {
    val first = patternFile("first", """##teamcity\[testStarted name='first' flowId='<flowId1>'\]""")
    val second = patternFile("second", """##teamcity\[testStarted name='first' flowId='<flowId9>'\]""")
    val expectations = ExpectationSet(Seq(
      FlowScope("one", Seq(AssertionGroup("first", first.getAbsolutePath))),
      FlowScope("two", Seq(AssertionGroup("second", second.getAbsolutePath)))
    ))

    val error = expectIllegalArgument {
      SbtOutputVerifier.validateExpectationSet(expectations, new File("."))
    }
    Assert.assertTrue(error.getMessage.contains("Duplicate expected-output assertion groups"))
    Assert.assertTrue(error.getMessage.contains("one/first"))
    Assert.assertTrue(error.getMessage.contains("two/second"))
  }

  @Test
  def expectationValidationNormalisesLineEndingsBeforeDuplicateComparison(): Unit = {
    val first = patternFile("first", "first", "second")
    val second = FileUtils.createTempFile("second", ".txt")
    Files.writeString(second.toPath, "first\r\nsecond\r\n")
    val expectations = ExpectationSet(Seq(
      FlowScope("one", Seq(AssertionGroup("first", first.getAbsolutePath))),
      FlowScope("two", Seq(AssertionGroup("second", second.getAbsolutePath)))
    ))

    Assert.assertTrue(expectIllegalArgument {
      SbtOutputVerifier.validateExpectationSet(expectations, new File("."))
    }.getMessage.contains("Duplicate expected-output assertion groups"))
  }

  @Test
  def expectationValidationRejectsMalformedAndEmptyGroups(): Unit = {
    val malformed = patternFile("malformed", """##teamcity\[testStarted name='test' flowId='<flowId0>'\]""")
    val malformedSet = ExpectationSet.oneScope("scope", AssertionGroup("malformed", malformed.getAbsolutePath))
    Assert.assertTrue(expectIllegalArgument {
      SbtOutputVerifier.validateExpectationSet(malformedSet, new File("."))
    }.getMessage.contains("Malformed flow-ID placeholder"))

    val empty = emptyPatternFile("empty")
    val emptySet = ExpectationSet.oneScope("scope", AssertionGroup("empty", empty.getAbsolutePath))
    Assert.assertTrue(expectIllegalArgument {
      SbtOutputVerifier.validateExpectationSet(emptySet, new File("."))
    }.getMessage.contains("is empty"))

    val nonPositiveSet = ExpectationSet.oneScope("scope", AssertionGroup("count", malformed.getAbsolutePath, minimumOccurrences = 0))
    Assert.assertTrue(expectIllegalArgument {
      SbtOutputVerifier.validateExpectationSet(nonPositiveSet, new File("."))
    }.getMessage.contains("minimumOccurrences >= 1"))
  }

  @Test
  def expectationValidationRejectsDuplicateNamesAndEmptyScopes(): Unit = {
    val valid = patternFile("valid", "expected")
    val duplicateScopeNames = ExpectationSet(Seq(
      FlowScope("scope", Seq(AssertionGroup("first", valid.getAbsolutePath))),
      FlowScope("scope", Seq(AssertionGroup("second", valid.getAbsolutePath)))
    ))
    Assert.assertTrue(expectIllegalArgument {
      SbtOutputVerifier.validateExpectationSet(duplicateScopeNames, new File("."))
    }.getMessage.contains("Duplicate flow-scope names"))

    val duplicateGroupNames = ExpectationSet(Seq(
      FlowScope("first", Seq(AssertionGroup("group", valid.getAbsolutePath))),
      FlowScope("second", Seq(AssertionGroup("group", valid.getAbsolutePath)))
    ))
    Assert.assertTrue(expectIllegalArgument {
      SbtOutputVerifier.validateExpectationSet(duplicateGroupNames, new File("."))
    }.getMessage.contains("Duplicate assertion-group names"))

    Assert.assertTrue(expectIllegalArgument {
      SbtOutputVerifier.validateExpectationSet(ExpectationSet(Seq(FlowScope("empty", Seq.empty))), new File("."))
    }.getMessage.contains("must contain at least one assertion group"))
  }

  @Test
  def groupsInOneScopeMayShareFlowsWithoutARelativeOrder(): Unit = {
    val first = patternFile("first", """##teamcity\[testStarted name='first' flowId='<flowId1>'\]""")
    val second = patternFile("second", """##teamcity\[testStarted name='second' flowId='<flowId1>'\]""")
    val expectations = ExpectationSet.oneScope(
      "shared",
      AssertionGroup("first", first.getAbsolutePath),
      AssertionGroup("second", second.getAbsolutePath)
    )

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testStarted name='second' flowId='runner']
        |##teamcity[testStarted name='first' flowId='runner']
        |""".stripMargin,
      excludesFile = None,
      expectations = expectations,
      fixtureDirectory = new File(".")
    )
  }

  @Test
  def flowScopeOwnershipBacktracksToACompatibleBinding(): Unit = {
    val first = patternFile("first", """##teamcity\[testStarted name='first' flowId='<flowId1>'\]""")
    val second = patternFile("second", """##teamcity\[testStarted name='second' flowId='<flowId1>'\]""")
    val expectations = ExpectationSet(Seq(
      FlowScope("first-scope", Seq(AssertionGroup("first", first.getAbsolutePath))),
      FlowScope("second-scope", Seq(AssertionGroup("second", second.getAbsolutePath)))
    ))

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testStarted name='first' flowId='shared']
        |##teamcity[testStarted name='first' flowId='dedicated']
        |##teamcity[testStarted name='second' flowId='shared']
        |""".stripMargin,
      excludesFile = None,
      expectations = expectations,
      fixtureDirectory = new File(".")
    )
  }

  @Test
  def flowScopeOwnershipRejectsTheSameConcreteFlowInDifferentScopes(): Unit = {
    val first = patternFile("first", """##teamcity\[testStarted name='first' flowId='<flowId1>'\]""")
    val second = patternFile("second", """##teamcity\[testStarted name='second' flowId='<flowId1>'\]""")
    val expectations = ExpectationSet(Seq(
      FlowScope("first-scope", Seq(AssertionGroup("first", first.getAbsolutePath))),
      FlowScope("second-scope", Seq(AssertionGroup("second", second.getAbsolutePath)))
    ))

    val error = expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        """##teamcity[testStarted name='first' flowId='shared']
          |##teamcity[testStarted name='second' flowId='shared']
          |""".stripMargin,
        excludesFile = None,
        expectations = expectations,
        fixtureDirectory = new File(".")
      )
    }
    assertFailureMessageContains(error, "no globally compatible flow-scope assignment")
    assertFailureMessageContains(error, "first-scope: {shared}")
  }

  @Test
  def repeatedOccurrencesUseDistinctOutputLinesAndLocalBindings(): Unit = {
    val repeated = patternFile(
      "repeated",
      """##teamcity\[testStarted name='test' flowId='<flowId1>'\]""",
      """##teamcity\[testFinished name='test' flowId='<flowId1>'\]"""
    )
    val expectations = ExpectationSet.oneScope("repeated", AssertionGroup("event", repeated.getAbsolutePath, minimumOccurrences = 2))

    SbtOutputVerifier.checkOutputText(
      """##teamcity[testStarted name='test' flowId='one']
        |##teamcity[testFinished name='test' flowId='one']
        |##teamcity[testStarted name='test' flowId='two']
        |##teamcity[testFinished name='test' flowId='two']
        |""".stripMargin,
      excludesFile = None,
      expectations = expectations,
      fixtureDirectory = new File(".")
    )
  }

  @Test
  def repeatedOccurrencesCannotReuseTheSameOutputLines(): Unit = {
    val repeated = patternFile(
      "repeated",
      """##teamcity\[testStarted name='test' flowId='<flowId1>'\]""",
      """##teamcity\[testFinished name='test' flowId='<flowId1>'\]"""
    )
    val expectations = ExpectationSet.oneScope("repeated", AssertionGroup("event", repeated.getAbsolutePath, minimumOccurrences = 2))

    val error = expectAssertionError {
      SbtOutputVerifier.checkOutputText(
        """##teamcity[testStarted name='test' flowId='one']
          |##teamcity[testFinished name='test' flowId='one']
          |""".stripMargin,
        excludesFile = None,
        expectations = expectations,
        fixtureDirectory = new File(".")
      )
    }
    assertFailureMessageContains(error, "requires 2 distinct occurrences")
  }

  private def patternFile(prefix: String, lines: String*): File = {
    val file = FileUtils.createTempFile(prefix, ".txt")
    FileUtils.writeLinesTo(file, lines*)
    file
  }

  private def emptyPatternFile(prefix: String): File = {
    val file = FileUtils.createTempFile(prefix, ".txt")
    Files.writeString(file.toPath, "")
    file
  }

  private def expectAssertionError(block: => Unit): AssertionError = {
    try {
      block
    } catch {
      case e: AssertionError =>
        return e
    }

    Assert.fail("Expected the verifier to throw AssertionError, but it completed successfully")
    throw new AssertionError("unreachable")
  }

  private def expectIllegalArgument(block: => Unit): IllegalArgumentException = {
    try {
      block
    } catch {
      case e: IllegalArgumentException => return e
    }

    Assert.fail("Expected IllegalArgumentException, but the verifier completed successfully")
    throw new IllegalArgumentException("unreachable")
  }

  private def assertFailureMessageContains(error: AssertionError, expectedText: String): Unit = {
    val actualMessage = Option(error.getMessage).getOrElse("<no failure message>")
    Assert.assertTrue(
      s"Expected verifier failure message to contain '$expectedText', but was: $actualMessage",
      actualMessage.contains(expectedText)
    )
  }
}
