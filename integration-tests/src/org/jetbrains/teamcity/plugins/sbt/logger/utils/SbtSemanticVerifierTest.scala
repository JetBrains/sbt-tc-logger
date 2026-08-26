package org.jetbrains.teamcity.plugins.sbt.logger.utils

import org.junit.{Assert, Test}

class SbtSemanticVerifierTest {
  import ObservedServiceMessageKind.*
  import SemanticValuePattern.*
  import SbtVerificationFailureCategory.*
  import SbtFindingDisposition.*

  private val flowA = SemanticBindingKey.flow("compile-a")
  private val flowB = SemanticBindingKey.flow("compile-b")

  @Test def observationRetainsOfficialParserDataAndRawSourceContext(): Unit = {
    val raw = "##teamcity[testFinished name='suite.test' duration='17' flowId='flow-1']"
    val run = SbtOutputObservation.parseBounded(
      Vector("ordinary", raw),
      exitCode = 3,
      loggerVersion = Some("2026.1-test"),
      sourceIndexOffset = 10
    )

    Assert.assertEquals(Some(ObservedHandshake("2026.1-test")), run.handshake)
    Assert.assertEquals(3, run.exitCode)
    Assert.assertEquals(Vector(ObservedPlainLine(10, "ordinary")), run.plainOutput)
    val message = run.serviceMessages.head
    Assert.assertEquals(11, message.sourceIndex)
    Assert.assertEquals(12, message.sourceLineNumber)
    Assert.assertEquals(raw, message.rawLine)
    Assert.assertEquals(TestFinished, message.kind)
    Assert.assertEquals(Some("17"), message.attribute("duration"))
    Assert.assertEquals("flow-1", message.officialMessage.getAttributes.get("flowId"))
  }

  @Test def parserCollectsAllMalformedLinesAndVerifierContinuesUnaffectedChecks(): Unit = {
    val contract = SbtSemanticContract(
      events = Vector(event("expected", BuildLogMessage, "expected")),
      processResult = ProcessResultContract.ExitCode(0)
    )
    val error = expectFailure {
      SbtSemanticOutputVerifier.verify(Vector(
        "prefix ##teamcity[message text='broken']",
        "plain surprise",
        "##teamcity[testStarted flowId='missing-name']"
      ), contract, exitCode = 7)
    }

    Assert.assertEquals(2, error.failures.count(_.category == WireProtocolFailure))
    Assert.assertTrue(error.failures.exists(_.category == PlainOutputFailure))
    Assert.assertTrue(error.failures.exists(_.category == ProcessResultFailure))
    Assert.assertFalse("Nested service messages must be sanitized", error.getMessage.contains("##teamcity["))
    Assert.assertTrue(error.getMessage.contains("source line 1"))
    Assert.assertTrue(error.getMessage.contains("source line 3"))
  }

  @Test def defaultDenyReportsMissingExtraAndWrongAttributes(): Unit = {
    val contract = compilationContract()

    val missing = expectFailure(verify(validCompilationOutput().patch(1, Nil, 1), contract))
    assertCategory(missing, SemanticCardinalityFailure)

    val extraLine = "##teamcity[message status='NORMAL' flowId='flow-a' text='unexpected']"
    val extra = expectFailure(verify(validCompilationOutput().patch(2, Vector(extraLine), 0), contract))
    assertCategory(extra, SemanticCardinalityFailure)

    val wrongAttribute = expectFailure(verify(
      validCompilationOutput().updated(1, buildMessage("flow-a", "wrong-a")),
      contract
    ))
    assertCategory(wrongAttribute, SemanticCardinalityFailure)
    Assert.assertTrue(wrongAttribute.getMessage.contains("compile-a-message"))
    Assert.assertTrue(wrongAttribute.getMessage.contains("Candidate source line 2"))
  }

  @Test def namedTypedBindingsRejectSwappedOwnershipAndNonDistinctFlows(): Unit = {
    val swapped = validCompilationOutput()
      .updated(1, buildMessage("flow-b", "body-a"))
      .updated(4, buildMessage("flow-a", "body-b"))
    assertCategory(expectFailure(verify(swapped, compilationContract())), FlowOwnershipFailure)

    val sharedFlow = validCompilationOutput().map(_.replace("flow-b", "flow-a"))
    assertCategory(expectFailure(verify(sharedFlow, compilationContract())), FlowOwnershipFailure)
  }

  @Test def typedEmbeddedBuildIdAndPathBindingsAreValidatedAndReused(): Unit = {
    val buildId = SemanticBindingKey.buildId("root")
    val sourceRoot = SemanticBindingKey.path("source-root")
    val contract = SbtSemanticContract(events = Vector(
      ExpectedSemanticEvent("first", BuildLogMessage,
        "status" -> exact("NORMAL"),
        "flowId" -> embedded("", buildId, ":compile"),
        "text" -> embedded("source=", sourceRoot, "/A.scala")
      ),
      ExpectedSemanticEvent("second", BuildLogMessage,
        "status" -> exact("NORMAL"),
        "flowId" -> embedded("", buildId, ":compile"),
        "text" -> embedded("again=", sourceRoot, "/B.scala")
      )
    ))
    val valid = Vector(
      "##teamcity[message status='NORMAL' flowId='-42:compile' text='source=/repo/A.scala']",
      "##teamcity[message status='NORMAL' flowId='-42:compile' text='again=/repo/B.scala']"
    )

    verify(valid, contract)
    assertCategory(expectFailure(verify(
      valid.updated(1, valid(1).replace("-42:compile", "not-a-build-id:compile")),
      contract
    )), SemanticCardinalityFailure)
    assertCategory(expectFailure(verify(
      valid.updated(1, valid(1).replace("again=/repo", "again=/other")),
      contract
    )), SemanticCardinalityFailure)
  }

  @Test def reusableLifecycleRulesRejectUnbalancedAndLocallyReorderedEvents(): Unit = {
    val unbalanced = expectFailure(verify(validCompilationOutput().dropRight(1), compilationContract()))
    assertCategory(unbalanced, LifecycleFailure)
    assertCategory(unbalanced, SemanticCardinalityFailure)

    val reordered = Vector(
      buildMessage("flow-a", "body-a"),
      compilationStarted("compiler-a", "flow-a"),
      compilationFinished("compiler-a", "flow-a"),
      compilationStarted("compiler-b", "flow-b"),
      buildMessage("flow-b", "body-b"),
      compilationFinished("compiler-b", "flow-b")
    )
    assertCategory(expectFailure(verify(reordered, compilationContract())), OrderingFailure)
  }

  @Test def explicitDagAcceptsEveryIndependentInterleaving(): Unit = {
    val laneA = Vector(
      compilationStarted("compiler-a", "flow-a"),
      buildMessage("flow-a", "body-a"),
      compilationFinished("compiler-a", "flow-a")
    )
    val laneB = Vector(
      compilationStarted("compiler-b", "flow-b"),
      buildMessage("flow-b", "body-b"),
      compilationFinished("compiler-b", "flow-b")
    )
    val all = interleavings(laneA, laneB)

    Assert.assertEquals(20, all.size)
    all.foreach(lines => verify(lines, compilationContract()))
  }

  @Test def globalMemberFlowBindingsDisambiguateOtherwiseIdenticalLifecycleBoundaries(): Unit = {
    val contract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("compile-a-start", CompilationStarted, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-a-message", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("body-a")),
        ExpectedSemanticEvent("compile-a-finish", CompilationFinished, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-b-start", CompilationStarted, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-b-message", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("body-b")),
        ExpectedSemanticEvent("compile-b-finish", CompilationFinished, "compiler" -> exact("same-compiler"))
      ),
      lifecycles = Vector(
        SemanticLifecycleRule.compilation(
          "compile-a", "compile-a-start", Seq("compile-a-message"), "compile-a-finish", flowA),
        SemanticLifecycleRule.compilation(
          "compile-b", "compile-b-start", Seq("compile-b-message"), "compile-b-finish", flowB)
      ),
      distinctBindings = Set(DistinctSemanticBindings(flowA, flowB))
    )
    val output = Vector(
      compilationStarted("same-compiler", "flow-a"),
      compilationStarted("same-compiler", "flow-b"),
      buildMessage("flow-b", "body-b"),
      buildMessage("flow-a", "body-a"),
      compilationFinished("same-compiler", "flow-b"),
      compilationFinished("same-compiler", "flow-a")
    )

    Assert.assertTrue(collect(output, contract).isEmpty)
    verify(output, contract)
  }

  @Test def ambiguousCompleteLifecyclesBlockIdentityDependentFindingsWithoutFalseViolations(): Unit = {
    val contract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("compile-a-start", CompilationStarted, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-a-finish", CompilationFinished, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-b-start", CompilationStarted, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-b-finish", CompilationFinished, "compiler" -> exact("same-compiler"))
      ),
      lifecycles = Vector(
        SemanticLifecycleRule.compilation("compile-a", "compile-a-start", Seq.empty, "compile-a-finish", flowA),
        SemanticLifecycleRule.compilation("compile-b", "compile-b-start", Seq.empty, "compile-b-finish", flowB)
      ),
      distinctBindings = Set(DistinctSemanticBindings(flowA, flowB))
    )
    val output = Vector(
      compilationStarted("same-compiler", "flow-a"),
      compilationStarted("same-compiler", "flow-b"),
      compilationFinished("same-compiler", "flow-a"),
      compilationFinished("same-compiler", "flow-b")
    )
    val findings = collect(output, contract)

    Assert.assertTrue(findings.exists(finding =>
      finding.category == MatcherComplexityFailure && finding.disposition == Violation))
    val lifecycles = findings.filter(_.category == LifecycleFailure)
    Assert.assertEquals(2, lifecycles.size)
    Assert.assertTrue(lifecycles.forall(_.disposition == Blocked))
    Assert.assertFalse(findings.exists(finding =>
      (finding.category == LifecycleFailure || finding.category == OrderingFailure ||
        finding.category == FlowOwnershipFailure) && finding.disposition == Violation))
  }

  @Test def matcherBudgetExhaustionDoesNotBlockSafelyMappedValidDependencies(): Unit = {
    val findings = collect(
      validCompilationOutput(),
      compilationContract().copy(matcherStateBudget = 1)
    )

    Assert.assertTrue(findings.exists(finding =>
      finding.category == MatcherComplexityFailure && finding.disposition == Violation))
    Seq(LifecycleFailure, FlowOwnershipFailure, OrderingFailure).foreach { category =>
      Assert.assertFalse(s"Unexpected $category finding", findings.exists(_.category == category))
    }
  }

  @Test def inconclusiveLaneDoesNotHideIndependentReversalOrBindingConflict(): Unit = {
    val sharedValue = SemanticBindingKey.value("independent-value")
    val contract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("compile-a-start", CompilationStarted, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-a-finish", CompilationFinished, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-b-start", CompilationStarted, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-b-finish", CompilationFinished, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("independent-first", BuildLogMessage,
          "status" -> exact("NORMAL"), "flowId" -> exact("independent-first"), "text" -> bound(sharedValue)),
        ExpectedSemanticEvent("independent-second", BuildLogMessage,
          "status" -> exact("NORMAL"), "flowId" -> exact("independent-second"), "text" -> bound(sharedValue))
      ),
      happensBefore = Set(HappensBefore("independent-first", "independent-second")),
      lifecycles = Vector(
        SemanticLifecycleRule.compilation("compile-a", "compile-a-start", Seq.empty, "compile-a-finish", flowA),
        SemanticLifecycleRule.compilation("compile-b", "compile-b-start", Seq.empty, "compile-b-finish", flowB)
      ),
      distinctBindings = Set(DistinctSemanticBindings(flowA, flowB)),
      matcherStateBudget = 1
    )
    val output = Vector(
      buildMessage("independent-second", "beta"),
      compilationStarted("same-compiler", "flow-a"),
      compilationStarted("same-compiler", "flow-b"),
      compilationFinished("same-compiler", "flow-a"),
      compilationFinished("same-compiler", "flow-b"),
      buildMessage("independent-first", "alpha")
    )
    val findings = collect(output, contract)

    Assert.assertTrue(findings.exists(finding =>
      finding.category == MatcherComplexityFailure && finding.disposition == Violation))
    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "binding:value:independent-value" && finding.disposition == Violation))
    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "edge:independent-first->independent-second" &&
        finding.category == OrderingFailure && finding.disposition == Violation))
    Assert.assertTrue(findings.exists(finding =>
      finding.category == LifecycleFailure && finding.disposition == Blocked))
    Assert.assertFalse(findings.exists(finding =>
      finding.category == LifecycleFailure && finding.disposition == Violation))
  }

  @Test def zeroGlobalAssignmentBlocksOnlyMissingPartialLifecycleIdentities(): Unit = {
    val sharedValue = SemanticBindingKey.value("shared-member-value")
    val contract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("compile-a-start", CompilationStarted, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-a-message", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("body-a"), "value" -> bound(sharedValue)),
        ExpectedSemanticEvent("compile-a-finish", CompilationFinished, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-b-start", CompilationStarted, "compiler" -> exact("same-compiler")),
        ExpectedSemanticEvent("compile-b-message", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("body-b"), "value" -> bound(sharedValue)),
        ExpectedSemanticEvent("compile-b-finish", CompilationFinished, "compiler" -> exact("same-compiler"))
      ),
      lifecycles = Vector(
        SemanticLifecycleRule.compilation(
          "compile-a", "compile-a-start", Seq("compile-a-message"), "compile-a-finish", flowA),
        SemanticLifecycleRule.compilation(
          "compile-b", "compile-b-start", Seq("compile-b-message"), "compile-b-finish", flowB)
      ),
      distinctBindings = Set(DistinctSemanticBindings(flowA, flowB))
    )
    val output = Vector(
      compilationStarted("same-compiler", "flow-a"),
      compilationStarted("same-compiler", "flow-b"),
      "##teamcity[message status='NORMAL' flowId='flow-a' text='body-a' value='alpha']",
      "##teamcity[message status='NORMAL' flowId='flow-b' text='body-b' value='beta']",
      compilationFinished("same-compiler", "flow-a"),
      compilationFinished("same-compiler", "flow-b")
    )
    val findings = collect(output, contract)

    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "binding:value:shared-member-value" && finding.disposition == Violation))
    val lifecycles = findings.filter(_.category == LifecycleFailure)
    Assert.assertEquals(2, lifecycles.size)
    Assert.assertTrue(lifecycles.forall(finding =>
      finding.disposition == Blocked && finding.context.exists(_.contains("no complete semantic event assignment"))))
    Assert.assertFalse(lifecycles.exists(_.disposition == Violation))
  }

  @Test def ambiguityRequiresExplicitContractRefinement(): Unit = {
    val line = "##teamcity[message status='NORMAL' text='same']"
    val ambiguous = SbtSemanticContract(events = Vector(
      event("first", BuildLogMessage, "same"),
      event("second", BuildLogMessage, "same")
    ))
    assertCategory(expectFailure(verify(Vector(line, line), ambiguous)), MatcherComplexityFailure)

    val refined = ambiguous.copy(events = Vector(
      ExpectedSemanticEvent.occurrence("first", BuildLogMessage, 1,
        "status" -> exact("NORMAL"), "text" -> exact("same")),
      ExpectedSemanticEvent.occurrence("second", BuildLogMessage, 2,
        "status" -> exact("NORMAL"), "text" -> exact("same"))
    ))
    verify(Vector(line, line), refined)
  }

  @Test def cyclicContractsAreRejectedBeforeMatching(): Unit = {
    val contract = SbtSemanticContract(
      events = Vector(event("a", BuildLogMessage, "a"), event("b", BuildLogMessage, "b")),
      happensBefore = Set(HappensBefore("a", "b"), HappensBefore("b", "a"))
    )
    assertCategory(expectFailure(verify(Vector(
      "##teamcity[message status='NORMAL' text='a']",
      "##teamcity[message status='NORMAL' text='b']"
    ), contract)), GoldenSyntaxFailure)
  }

  @Test def simultaneousIndependentViolationsAreRenderedTogether(): Unit = {
    val wrongOwnershipAndOrder = Vector(
      buildMessage("flow-b", "body-a"),
      compilationStarted("compiler-a", "flow-a"),
      compilationFinished("compiler-a", "flow-a"),
      compilationStarted("compiler-b", "flow-b"),
      buildMessage("flow-a", "body-b"),
      compilationFinished("compiler-b", "flow-b"),
      "unexpected plain output"
    )
    val contract = compilationContract().copy(processResult = ProcessResultContract.ExitCode(0))
    val error = expectFailure(SbtSemanticOutputVerifier.verify(wrongOwnershipAndOrder, contract, exitCode = 9))

    assertCategory(error, FlowOwnershipFailure)
    assertCategory(error, OrderingFailure)
    assertCategory(error, PlainOutputFailure)
    assertCategory(error, ProcessResultFailure)
    Assert.assertTrue(error.getMessage.startsWith("Semantic output verification found"))
    Assert.assertTrue(error.getMessage.contains("violations, 0 blocked"))
    Assert.assertTrue(error.getMessage.contains("[FlowOwnershipFailure]"))
  }

  @Test def missingEventDoesNotMaskIndependentLaneOwnershipAndOrderingFailures(): Unit = {
    val output = Vector(
      compilationStarted("compiler-a", "flow-a"),
      compilationFinished("compiler-a", "flow-a"),
      buildMessage("flow-a", "body-b"),
      compilationStarted("compiler-b", "flow-b"),
      compilationFinished("compiler-b", "flow-b")
    )
    val error = expectFailure(verify(output, compilationContract()))

    assertCategory(error, SemanticCardinalityFailure)
    assertCategory(error, FlowOwnershipFailure)
    assertCategory(error, OrderingFailure)
  }

  @Test def malformedLineDoesNotMaskUnaffectedSemanticViolationsAndBlocksOnlyDependentProofs(): Unit = {
    val output = Vector(
      compilationStarted("compiler-a", "flow-a"),
      "##teamcity[message status='NORMAL' flowId='flow-a' text='body-a'",
      compilationFinished("compiler-a", "flow-a"),
      buildMessage("flow-b", "body-b"),
      compilationStarted("compiler-b", "flow-b"),
      compilationFinished("compiler-b", "flow-b")
    )
    val findings = collect(output, compilationContract())

    Assert.assertTrue(findings.exists(finding =>
      finding.category == WireProtocolFailure && finding.disposition == Violation))
    Assert.assertTrue(findings.exists(finding =>
      finding.category == OrderingFailure && finding.disposition == Violation &&
        finding.semanticIdentity == "edge:compile-b-start->compile-b-message"))
    val blockedEdges = findings.filter(finding =>
      finding.category == OrderingFailure && finding.disposition == Blocked)
    Assert.assertEquals(2, blockedEdges.size)
    Assert.assertTrue(blockedEdges.forall(_.semanticIdentity.contains("compile-a-message")))
    Assert.assertTrue(findings.exists(finding =>
      finding.category == SemanticCardinalityFailure && finding.disposition == Blocked))
  }

  @Test def malformedLineBlocksRequiredEventAttributeConclusion(): Unit = {
    val contract = SbtSemanticContract(Vector(event("required", BuildLogMessage, "expected")))
    val wrongParsedEvent = "##teamcity[message status='NORMAL' text='wrong']"
    val baseline = collect(Vector(wrongParsedEvent), contract)
    Assert.assertTrue(baseline.exists(finding =>
      finding.semanticIdentity == "event:required" && finding.disposition == Violation))

    val withMalformedCandidate = collect(Vector(
      wrongParsedEvent,
      "##teamcity[message status='NORMAL' text='expected'"
    ), contract)
    Assert.assertTrue(withMalformedCandidate.exists(_.category == WireProtocolFailure))
    Assert.assertTrue(withMalformedCandidate.exists(finding =>
      finding.semanticIdentity == "event:required" && finding.disposition == Blocked &&
        finding.context.exists(_.contains("Malformed lines"))))
  }

  @Test def distinctBindingsMustReferenceCapturedExpandedKeys(): Unit = {
    val typo = SemanticBindingKey.flow("typo")
    val contract = SbtSemanticContract(
      events = Vector(ExpectedSemanticEvent("only", BuildLogMessage,
        "status" -> exact("NORMAL"), "flowId" -> bound(flowA), "text" -> exact("only"))),
      distinctBindings = Set(DistinctSemanticBindings(flowA, typo))
    )
    val findings = collect(Vector(buildMessage("flow-a", "only")), contract)

    Assert.assertTrue(findings.exists(finding =>
      finding.category == GoldenSyntaxFailure && finding.summary.contains("not captured") &&
        finding.summary.contains("typo")))
  }

  @Test def nonOwnershipBindingConflictsIdentifyExpectedReusedAndConflictingRawValues(): Unit = {
    val cases = Vector(
      nonOwnershipCase(
        SemanticBindingKey.path("shared-path"),
        Vector(
          buildMessage("one", "/repo"),
          buildMessage("two", "/repo"),
          buildMessage("three", "/other")
        ),
        Vector(
          ExpectedSemanticEvent("first", BuildLogMessage,
            "status" -> exact("NORMAL"), "flowId" -> exact("one"), "text" -> bound(SemanticBindingKey.path("shared-path"))),
          ExpectedSemanticEvent("second", BuildLogMessage,
            "status" -> exact("NORMAL"), "flowId" -> exact("two"), "text" -> bound(SemanticBindingKey.path("shared-path"))),
          ExpectedSemanticEvent("third", BuildLogMessage,
            "status" -> exact("NORMAL"), "flowId" -> exact("three"), "text" -> bound(SemanticBindingKey.path("shared-path")))
        )
      ),
      nonOwnershipCase(
        SemanticBindingKey.duration("shared-duration"),
        Vector(
          "##teamcity[testFinished name='first' duration='10' flowId='one']",
          "##teamcity[testFinished name='second' duration='10' flowId='two']",
          "##teamcity[testFinished name='third' duration='11' flowId='three']"
        ),
        Vector(
          ExpectedSemanticEvent("first", TestFinished,
            "name" -> exact("first"), "duration" -> bound(SemanticBindingKey.duration("shared-duration")), "flowId" -> exact("one")),
          ExpectedSemanticEvent("second", TestFinished,
            "name" -> exact("second"), "duration" -> bound(SemanticBindingKey.duration("shared-duration")), "flowId" -> exact("two")),
          ExpectedSemanticEvent("third", TestFinished,
            "name" -> exact("third"), "duration" -> bound(SemanticBindingKey.duration("shared-duration")), "flowId" -> exact("three"))
        )
      ),
      nonOwnershipCase(
        SemanticBindingKey.value("shared-value"),
        Vector(
          buildMessage("one", "alpha"),
          buildMessage("two", "alpha"),
          buildMessage("three", "beta")
        ),
        Vector(
          ExpectedSemanticEvent("first", BuildLogMessage,
            "status" -> exact("NORMAL"), "flowId" -> exact("one"), "text" -> bound(SemanticBindingKey.value("shared-value"))),
          ExpectedSemanticEvent("second", BuildLogMessage,
            "status" -> exact("NORMAL"), "flowId" -> exact("two"), "text" -> bound(SemanticBindingKey.value("shared-value"))),
          ExpectedSemanticEvent("third", BuildLogMessage,
            "status" -> exact("NORMAL"), "flowId" -> exact("three"), "text" -> bound(SemanticBindingKey.value("shared-value")))
        )
      )
    )

    cases.foreach { case (key, output, contract) =>
      val findings = collect(output, contract)
      val conflict = findings.find(_.semanticIdentity == s"binding:${key.kind.displayName}:${key.name}").getOrElse {
        throw new AssertionError(s"Missing detailed conflict for $key: $findings")
      }
      Assert.assertEquals(SemanticCardinalityFailure, conflict.category)
      val diagnostic = (conflict.summary +: conflict.context).mkString("\n")
      Assert.assertTrue(diagnostic.contains("Expected/reused value"))
      Assert.assertTrue(diagnostic.contains("Reused value"))
      Assert.assertTrue(diagnostic.contains("Conflicting value"))
      Assert.assertTrue(diagnostic.contains("event 'first'"))
      Assert.assertTrue(diagnostic.contains("event 'second'"))
      Assert.assertTrue(diagnostic.contains("event 'third'"))
      Assert.assertTrue(diagnostic.contains("Raw: ##teamcity["))
      Assert.assertFalse(findings.exists(_.summary.contains("cannot be assigned one-to-one")))
    }
  }

  @Test def attributeDiagnosticsRetainAllCandidatesBeyondThree(): Unit = {
    val contract = SbtSemanticContract(events = Vector(event("target", BuildLogMessage, "target")))
    val output = (1 to 5).map(index => s"##teamcity[message status='NORMAL' text='wrong-$index']").toVector
    val findings = collect(output, contract)
    val mismatch = findings.find(_.semanticIdentity == "event:target").get
    val context = mismatch.context.mkString("\n")

    (1 to 5).foreach(index => Assert.assertTrue(context.contains(s"wrong-$index")))
    Assert.assertEquals(5, mismatch.context.count(_.startsWith("Candidate source line")))
  }

  @Test def attributeDiagnosticsNameUnexpectedKeys(): Unit = {
    val contract = SbtSemanticContract(events = Vector(event("target", BuildLogMessage, "target")))
    val findings = collect(Vector(
      "##teamcity[message status='NORMAL' text='target' surprise='unexpected']"
    ), contract)

    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "event:target" && finding.context.exists(_.contains("unexpected keys: surprise"))))
  }

  @Test def delegatedChecksRemainBlockedUntilHarnessEvidenceIsSupplied(): Unit = {
    val contract = SbtSemanticContract(
      events = Vector(event("target", BuildLogMessage, "target")),
      plainOutput = PlainOutputContract.DelegatedToHybrid,
      processResult = ProcessResultContract.DelegatedToHarness
    )
    val output = Vector("##teamcity[message status='NORMAL' text='target']")
    val blocked = SbtSemanticOutputVerifier.collect(output, contract, exitCode = 0)

    Assert.assertTrue(blocked.exists(finding =>
      finding.category == PlainOutputFailure && finding.disposition == Blocked))
    Assert.assertTrue(blocked.exists(finding =>
      finding.category == ProcessResultFailure && finding.disposition == Blocked))
    Assert.assertTrue(SbtSemanticOutputVerifier.collect(
      output,
      contract,
      exitCode = 0,
      delegated = SbtDelegatedVerification(plainOutputVerified = true, processResultVerified = true)
    ).isEmpty)
  }

  @Test def processSuccessAndFailureContractsUseZeroVersusAnyNonzeroExitCode(): Unit = {
    val output = Vector("##teamcity[message status='NORMAL' text='target']")
    val base = SbtSemanticContract(
      events = Vector(event("target", BuildLogMessage, "target")),
      processResult = ProcessResultContract.Success
    )

    Assert.assertFalse(SbtSemanticOutputVerifier.collect(output, base, exitCode = 0).exists(
      _.category == ProcessResultFailure))
    Assert.assertTrue(SbtSemanticOutputVerifier.collect(output, base, exitCode = 3).exists(
      _.category == ProcessResultFailure))
    Assert.assertFalse(SbtSemanticOutputVerifier.collect(
      output,
      base.copy(processResult = ProcessResultContract.Failure),
      exitCode = 3
    ).exists(_.category == ProcessResultFailure))
    Assert.assertTrue(SbtSemanticOutputVerifier.collect(
      output,
      base.copy(processResult = ProcessResultContract.Failure),
      exitCode = 0
    ).exists(_.category == ProcessResultFailure))
  }

  @Test def emptyEventContractStrictlyRequiresProtocolAbsence(): Unit = {
    val contract = SbtSemanticContract(events = Vector.empty)

    verify(Vector.empty, contract)
    val findings = collect(Vector("##teamcity[message status='NORMAL' text='unexpected']"), contract)
    Assert.assertTrue(findings.exists(finding =>
      finding.category == SemanticCardinalityFailure && finding.disposition == Violation))
    Assert.assertFalse(findings.exists(_.category == GoldenSyntaxFailure))
  }

  @Test def lifecycleOwnershipSupportsOneBuildIdAcrossMainAndTestSuffixes(): Unit = {
    val buildId = SemanticBindingKey.buildId("root-build")
    val mainOwnership = embedded("", buildId, ":compile:compiler")
    val testOwnership = embedded("", buildId, ":test:compiler")
    val contract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("main-start", CompilationStarted, "compiler" -> exact("main")),
        ExpectedSemanticEvent("main-message", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("main body")),
        ExpectedSemanticEvent("main-finish", CompilationFinished, "compiler" -> exact("main")),
        ExpectedSemanticEvent("test-start", CompilationStarted, "compiler" -> exact("test")),
        ExpectedSemanticEvent("test-message", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("test body")),
        ExpectedSemanticEvent("test-finish", CompilationFinished, "compiler" -> exact("test"))
      ),
      lifecycles = Vector(
        SemanticLifecycleRule.compilation(
          "main-compile", "main-start", Seq("main-message"), "main-finish", mainOwnership),
        SemanticLifecycleRule.compilation(
          "test-compile", "test-start", Seq("test-message"), "test-finish", testOwnership)
      )
    )
    val valid = Vector(
      compilationStarted("main", "42:compile:compiler"),
      buildMessage("42:compile:compiler", "main body"),
      compilationFinished("main", "42:compile:compiler"),
      compilationStarted("test", "42:test:compiler"),
      buildMessage("42:test:compiler", "test body"),
      compilationFinished("test", "42:test:compiler")
    )

    verify(valid, contract)
    val wrongBuild = valid.updated(4, buildMessage("43:test:compiler", "test body"))
    assertCategory(expectFailure(verify(wrongBuild, contract)), FlowOwnershipFailure)
    val swappedBoundary = valid.updated(3, compilationStarted("test", "42:compile:compiler"))
    assertCategory(expectFailure(verify(swappedBoundary, contract)), SemanticCardinalityFailure)
  }

  @Test def optionalCompilerBridgeGroupIsAbsentOrCompleteAndUsesMatcherBudget(): Unit = {
    val bridgeFlow = SemanticBindingKey.flow("bridge")
    val group = OptionalSemanticEventGroup(
      "compiler-bridge",
      Vector(
        ExpectedSemanticEvent("bridge-announcement", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> compilerBridgeAnnouncement),
        ExpectedSemanticEvent("bridge-completion", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> compilerBridgeCompletion)
      ),
      happensBefore = Set(HappensBefore("bridge-announcement", "bridge-completion")),
      ownership = Some(bound(bridgeFlow))
    )
    val contract = SbtSemanticContract(events = Vector.empty, optionalGroups = Vector(group))
    val announcement =
      "##teamcity[message status='NORMAL' text='|[info|] Non-compiled module |'compiler-bridge_2.13|' for Scala 2.13.18. Compiling...' flowId='bridge-flow']"
    val completion =
      "##teamcity[message status='NORMAL' text='|[info|]   Compilation completed in 4.321s.' flowId='bridge-flow']"

    verify(Vector.empty, contract)
    verify(Vector(announcement, completion), contract)
    val partial = collect(Vector(announcement), contract)
    Assert.assertTrue(partial.exists(_.semanticIdentity == "optional-group:compiler-bridge"))
    Assert.assertTrue(partial.exists(finding =>
      finding.semanticIdentity == "kind:message" && finding.disposition == Violation))
    Assert.assertTrue(partial.exists(finding =>
      finding.semanticIdentity == "edge:bridge-announcement->bridge-completion" &&
        finding.disposition == Blocked))
    val malformed = SbtSemanticOutputVerifier.collect(
      Vector(announcement, completion.dropRight(1)),
      contract,
      exitCode = 0,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )
    Assert.assertTrue(malformed.exists(finding =>
      finding.semanticIdentity == "edge:bridge-announcement->bridge-completion" &&
        finding.disposition == Blocked && finding.context.exists(_.contains("Malformed lines"))))
    Assert.assertTrue(malformed.exists(finding =>
      finding.semanticIdentity == "event:bridge-completion" && finding.disposition == Blocked))
    Assert.assertTrue(malformed.exists(finding =>
      finding.semanticIdentity == "kind:message" && finding.disposition == Blocked &&
        finding.context.exists(_.contains("complete an allowed event count"))))
    assertCategory(expectFailure(verify(Vector(completion, announcement), contract)), OrderingFailure)
    assertCategory(expectFailure(verify(
      Vector(announcement, completion),
      contract.copy(matcherStateBudget = 1)
    )), MatcherComplexityFailure)
  }

  @Test def repeatedEquivalentEventsNeedNoObservedOrdinalsAndExpandEdgesAndLifecycleMembership(): Unit = {
    val compileFlow = SemanticBindingKey.flow("repeat-compile")
    val contract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("compile-start", CompilationStarted, "compiler" -> exact("compiler")),
        ExpectedSemanticEvent.repeated("compiler-message", BuildLogMessage, 2,
          "status" -> exact("NORMAL"), "text" -> exact("same")),
        ExpectedSemanticEvent("compile-finish", CompilationFinished, "compiler" -> exact("compiler")),
        ExpectedSemanticEvent("inspection", Inspection,
          "typeId" -> exact("scala"), "message" -> exact("checked"))
      ),
      happensBefore = Set(HappensBefore("compiler-message", "inspection")),
      lifecycles = Vector(SemanticLifecycleRule.compilation(
        "compile", "compile-start", Seq("compiler-message"), "compile-finish", compileFlow))
    )
    val valid = Vector(
      compilationStarted("compiler", "repeat-flow"),
      buildMessage("repeat-flow", "same"),
      buildMessage("repeat-flow", "same"),
      compilationFinished("compiler", "repeat-flow"),
      "##teamcity[inspection typeId='scala' message='checked']"
    )

    verify(valid, contract)
    Assert.assertFalse(collect(valid, contract).exists(_.category == MatcherComplexityFailure))
    val oneCloneOutsideLifecycle = valid.updated(0, buildMessage("repeat-flow", "same")).updated(
      1, compilationStarted("compiler", "repeat-flow"))
    assertCategory(expectFailure(verify(oneCloneOutsideLifecycle, contract)), OrderingFailure)

    val oneMissingClone = Vector(
      compilationStarted("compiler", "repeat-flow"),
      compilationFinished("compiler", "repeat-flow"),
      buildMessage("repeat-flow", "same"),
      "##teamcity[inspection typeId='scala' message='checked']"
    )
    val partial = collect(oneMissingClone, contract)
    Assert.assertTrue(partial.exists(finding =>
      finding.semanticIdentity == "edge:compiler-message.1->compile-finish" && finding.disposition == Violation))
    Assert.assertTrue(partial.exists(finding =>
      finding.semanticIdentity.contains("compiler-message.2") && finding.disposition == Blocked))
  }

  @Test def partialCloneMappingDoesNotClaimObservationsSharedWithNonClones(): Unit = {
    val cloneFlow = SemanticBindingKey.flow("clone-flow")
    val nonCloneFlow = SemanticBindingKey.flow("non-clone-flow")
    val contract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent.repeated("clone", BuildLogMessage, 2,
          "status" -> exact("NORMAL"), "flowId" -> bound(cloneFlow), "text" -> exact("shared")),
        ExpectedSemanticEvent("non-clone", BuildLogMessage,
          "status" -> exact("NORMAL"), "flowId" -> bound(nonCloneFlow), "text" -> exact("shared")),
        ExpectedSemanticEvent("inspection", Inspection,
          "typeId" -> exact("scala"), "message" -> exact("checked"))
      ),
      happensBefore = Set(HappensBefore("clone", "inspection"))
    )
    val findings = collect(Vector(
      "##teamcity[inspection typeId='scala' message='checked']",
      buildMessage("flow-a", "shared"),
      buildMessage("flow-b", "shared")
    ), contract)

    Assert.assertFalse(findings.exists(finding =>
      Set(OrderingFailure, FlowOwnershipFailure).contains(finding.category) && finding.disposition == Violation))
    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "edge:clone.1->inspection" && finding.disposition == Blocked))
    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "binding:flow:clone-flow" && finding.disposition == Blocked))
  }

  @Test def finiteSemanticPatternsAcceptOnlyTheirNamedStructures(): Unit = {
    val url = "https://repo.example.test/a.jar"
    val contract = SbtSemanticContract(events = Vector(
      ExpectedSemanticEvent("duration", TestFinished,
        "name" -> exact("test"), "duration" -> unsignedDuration, "flowId" -> exact("test-flow")),
      ExpectedSemanticEvent.repeated("normal-resource", BuildLogMessage, 2,
        "status" -> exact("NORMAL"), "flowId" -> exact("dependency"),
        "text" -> dependencyResource("root", "global", url, Set(
          DependencyResourceOutcome.LocalCacheHit,
          DependencyResourceOutcome.Downloaded
        ))),
      ExpectedSemanticEvent("failed-resource", BuildLogMessage,
        "status" -> exact("WARNING"), "flowId" -> exact("dependency"),
        "text" -> dependencyResource("root", "global", url, Set(
          DependencyResourceOutcome.FailedDownloadAttempt
        ))),
      ExpectedSemanticEvent("failure", TestFailed,
        "name" -> exact("fixture.fails"),
        "details" -> userFailure(
          "java.lang.AssertionError: boom",
          Seq("\tat fixture.Spec.fails(Spec.scala:7)"),
          RecognizedTestFramework.JUnit,
          maximumFrameworkFrames = 2
        ),
        "flowId" -> exact("test-flow"))
    ))
    val output = Vector(
      "##teamcity[testFinished name='test' duration='17.5' flowId='test-flow']",
      s"##teamcity[message status='NORMAL' flowId='dependency' text='|[root / global|] local cache hit $url']",
      s"##teamcity[message status='NORMAL' flowId='dependency' text='|[root / global|] downloaded $url (1.2 KiB, 17 ms)']",
      s"##teamcity[message status='WARNING' flowId='dependency' text='|[root / global|] failed download attempt $url (after 1.25 s)']",
      "##teamcity[testFailed name='fixture.fails' details='java.lang.AssertionError: boom|n\tat fixture.Spec.fails(Spec.scala:7)|n\tat org.junit.runners.ParentRunner.run(ParentRunner.java:1)|n\tat java.base/java.lang.reflect.Method.invoke(Method.java:2)' flowId='test-flow']"
    )

    verify(output, contract)
    assertCategory(expectFailure(verify(output.updated(0,
      "##teamcity[testFinished name='test' duration='-1' flowId='test-flow']"), contract)), SemanticCardinalityFailure)
    assertCategory(expectFailure(verify(output.updated(2, output(2).replace(url, url + "?mutable=true")), contract)),
      SemanticCardinalityFailure)
    assertCategory(expectFailure(verify(output.updated(4,
      output(4).replace("fixture.Spec.fails(Spec.scala:7)", "fixture.Spec.other(Spec.scala:8)")), contract)),
      SemanticCardinalityFailure)
  }

  @Test def finitePlainPatternsEnforceCardinalityAndOptionalRawBridgeBlocks(): Unit = {
    val patterns = PlainOutputContract.Patterns(Vector(
      PlainOutputPattern.SbtTaskSummary,
      PlainOutputPattern.OptionalGroup("raw-bridge", Vector(
        PlainOutputPattern.CompilerBridgeAnnouncement,
        PlainOutputPattern.CompilerBridgeCompletion
      ))
    ))
    val contract = SbtSemanticContract(events = Vector.empty, plainOutput = patterns)
    val summary = "[success] Total time: 1.25 s, completed Aug 26, 2026, 12:00:00 PM"
    val announcement = "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.18. Compiling..."
    val completion = "[info]   Compilation completed in 4.321s."

    verify(Vector(summary), contract)
    verify(Vector(summary, announcement, completion), contract)
    assertCategory(expectFailure(verify(Vector.empty, contract)), PlainOutputFailure)
    assertCategory(expectFailure(verify(Vector(summary, summary), contract)), PlainOutputFailure)
    assertCategory(expectFailure(verify(Vector(summary, announcement), contract)), PlainOutputFailure)
    assertCategory(expectFailure(verify(
      Vector(summary, announcement, completion, announcement, completion), contract)), PlainOutputFailure)
  }

  @Test def scenarioShapedProtocolContractCoversKindsAttributesPlainOutputAndBoundaryOwnership(): Unit = {
    val suiteAndTestFlow = SemanticBindingKey.flow("suite-and-test")
    val blockFlow = SemanticBindingKey.flow("block")
    val contract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("suite-start", TestSuiteStarted, "name" -> exact("fixture.Suite")),
        ExpectedSemanticEvent("test-start", TestStarted,
          "name" -> exact("fixture.Suite.test"), "captureStandardOutput" -> exact("true")),
        ExpectedSemanticEvent("test-failed", TestFailed,
          "name" -> exact("fixture.Suite.test"), "details" -> exact("boom")),
        ExpectedSemanticEvent("test-finish", TestFinished,
          "name" -> exact("fixture.Suite.test"), "duration" -> unsignedDuration),
        ExpectedSemanticEvent("suite-finish", TestSuiteFinished, "name" -> exact("fixture.Suite")),
        ExpectedSemanticEvent("block-open", BlockOpened, "name" -> exact("analysis")),
        ExpectedSemanticEvent("inspection-type", InspectionType,
          "id" -> exact("scala"), "name" -> exact("Scala"), "description" -> exact("Compiler"),
          "category" -> exact("Compiler")),
        ExpectedSemanticEvent("inspection", Inspection,
          "typeId" -> exact("scala"), "message" -> exact("warning"), "file" -> exact("/repo/A.scala"),
          "line" -> exact("7"), "severity" -> exact("WARNING")),
        ExpectedSemanticEvent("custom", Other("fixtureEvent"), "value" -> exact("owned")),
        ExpectedSemanticEvent("block-close", BlockClosed, "name" -> exact("analysis"))
      ),
      lifecycles = Vector(
        SemanticLifecycleRule.suite(
          "suite", "suite-start", Seq("test-start", "test-failed", "test-finish"), "suite-finish", suiteAndTestFlow),
        SemanticLifecycleRule.test(
          "test", "test-start", Seq("test-failed"), "test-finish", suiteAndTestFlow),
        SemanticLifecycleRule.block(
          "analysis", "block-open", Seq("inspection-type", "inspection", "custom"), "block-close", blockFlow)
      ),
      plainOutput = PlainOutputContract.Exact(Vector("fixture output"))
    )
    val valid = Vector(
      "##teamcity[testSuiteStarted name='fixture.Suite' flowId='suite-test-flow']",
      "##teamcity[testStarted name='fixture.Suite.test' captureStandardOutput='true' flowId='suite-test-flow']",
      "##teamcity[testFailed name='fixture.Suite.test' details='boom' flowId='suite-test-flow']",
      "##teamcity[testFinished name='fixture.Suite.test' duration='9' flowId='suite-test-flow']",
      "##teamcity[testSuiteFinished name='fixture.Suite' flowId='suite-test-flow']",
      "##teamcity[blockOpened name='analysis' flowId='block-flow']",
      "##teamcity[inspectionType id='scala' name='Scala' description='Compiler' category='Compiler' flowId='block-flow']",
      "##teamcity[inspection typeId='scala' message='warning' file='/repo/A.scala' line='7' severity='WARNING' flowId='block-flow']",
      "##teamcity[fixtureEvent value='owned' flowId='block-flow']",
      "##teamcity[blockClosed name='analysis' flowId='block-flow']",
      "fixture output"
    )

    verify(valid, contract)
    assertCategory(expectFailure(verify(valid.updated(7,
      valid(7).replace(" severity='WARNING'", "")), contract)), SemanticCardinalityFailure)
    assertCategory(expectFailure(verify(valid.updated(8,
      valid(8).replace(" value='owned'", " value='owned' surprise='x'")), contract)), SemanticCardinalityFailure)
    val swapped = valid.updated(0, valid(0).replace("suite-test-flow", "block-flow"))
    assertCategory(expectFailure(verify(swapped, contract)), FlowOwnershipFailure)

    val declared = contract.copy(plainOutput = PlainOutputContract.Declared("fixture prefix", _.startsWith("fixture ")))
    verify(valid, declared)
    assertCategory(expectFailure(verify(valid.updated(10, "foreign output"), declared)), PlainOutputFailure)
  }

  @Test def lifecycleOwnershipAcceptsOnlyTheDeclaredNonEmptyExactFlow(): Unit = {
    def contract(ownership: SemanticValuePattern): SbtSemanticContract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("block-open", BlockOpened, "name" -> exact("dependency")),
        ExpectedSemanticEvent("resource", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("resource")),
        ExpectedSemanticEvent("block-close", BlockClosed, "name" -> exact("dependency"))
      ),
      lifecycles = Vector(SemanticLifecycleRule.block(
        "dependency", "block-open", Seq("resource"), "block-close", ownership
      ))
    )
    val valid = Vector(
      "##teamcity[blockOpened name='dependency' flowId='stable-dependency-flow']",
      "##teamcity[message status='NORMAL' text='resource' flowId='stable-dependency-flow']",
      "##teamcity[blockClosed name='dependency' flowId='stable-dependency-flow']"
    )
    val exactContract = contract(exact("stable-dependency-flow"))

    verify(valid, exactContract)
    val wrongFlow = expectFailure(verify(
      valid.map(_.replace("stable-dependency-flow", "other-flow")),
      exactContract
    ))
    assertCategory(wrongFlow, FlowOwnershipFailure)
    Assert.assertTrue(wrongFlow.getMessage.contains("Expected: 'stable-dependency-flow'"))
    Assert.assertTrue(wrongFlow.getMessage.contains("Observed: 'other-flow'"))
    Assert.assertFalse(wrongFlow.failures.exists(finding =>
      finding.category == SemanticCardinalityFailure && finding.disposition == SbtFindingDisposition.Violation))
    assertCategory(expectFailure(verify(Vector.empty, contract(exact("")))), GoldenSyntaxFailure)
  }

  @Test def orderBlindDiagnosticsSeparateReversalFromCardinalityAndAmbiguity(): Unit = {
    val unique = SbtSemanticContract(
      events = Vector(
        event("first", BuildLogMessage, "first"),
        event("second", BuildLogMessage, "second")
      ),
      happensBefore = Set(HappensBefore("first", "second"))
    )
    verify(Vector(message("NORMAL", "first"), message("NORMAL", "second")), unique)
    val uniqueReversal = collect(Vector(message("NORMAL", "second"), message("NORMAL", "first")), unique)
    Assert.assertTrue(uniqueReversal.exists(finding =>
      finding.category == OrderingFailure && finding.disposition == Violation &&
        finding.semanticIdentity == "edge:first->second"))
    Assert.assertFalse(uniqueReversal.exists(_.category == SemanticCardinalityFailure))
    Assert.assertFalse(uniqueReversal.exists(_.category == MatcherComplexityFailure))

    val orderShared = SemanticBindingKey.value("order-shared")
    val structurallyAmbiguous = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("shared-a", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("shared"), "value" -> bound(orderShared)),
        ExpectedSemanticEvent("shared-b", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("shared"), "value" -> bound(orderShared)),
        event("boundary", BuildLogMessage, "boundary")
      ),
      happensBefore = Set(
        HappensBefore("shared-a", "boundary"),
        HappensBefore("shared-b", "boundary")
      )
    )
    val ambiguousReversal = collect(Vector(
      message("NORMAL", "boundary"),
      "##teamcity[message status='NORMAL' text='shared' value='same']",
      "##teamcity[message status='NORMAL' text='shared' value='same']"
    ), structurallyAmbiguous)
    Assert.assertTrue(ambiguousReversal.exists(finding =>
      finding.category == OrderingFailure && finding.disposition == Violation &&
        finding.semanticIdentity == "event-assignment-ordering"))
    Assert.assertEquals(2, ambiguousReversal.count(finding =>
      finding.category == OrderingFailure && finding.disposition == Blocked &&
        finding.semanticIdentity.startsWith("edge:shared-")))
    Assert.assertTrue(ambiguousReversal.exists(finding =>
      finding.category == SemanticCardinalityFailure && finding.disposition == Blocked &&
        finding.semanticIdentity == "binding:value:order-shared"))
    Assert.assertFalse(ambiguousReversal.exists(finding =>
      finding.category == SemanticCardinalityFailure && finding.disposition == Violation))
    Assert.assertFalse(ambiguousReversal.exists(_.category == MatcherComplexityFailure))

    val budgetIds = Vector.tabulate(4)(index => s"budget-shared-${index + 1}")
    val budgetContract = SbtSemanticContract(
      events = budgetIds.map(id => event(id, BuildLogMessage, "budget-shared")) :+
        event("budget-boundary", BuildLogMessage, "budget-boundary"),
      happensBefore = budgetIds.map(id => HappensBefore(id, "budget-boundary")).toSet,
      matcherStateBudget = 4
    )
    val budgetFindings = collect(
      Vector(message("NORMAL", "budget-boundary")) ++
        Vector.fill(4)(message("NORMAL", "budget-shared")),
      budgetContract
    )
    Assert.assertTrue(budgetFindings.exists(finding =>
      finding.category == MatcherComplexityFailure && finding.disposition == Violation &&
        finding.semanticIdentity == "event-assignment-ordering-diagnostic"))
    Assert.assertFalse(budgetFindings.exists(finding =>
      finding.category == SemanticCardinalityFailure && finding.disposition == Violation))

    val uniquenessIds = Vector("uniqueness-shared-1", "uniqueness-shared-2")
    val uniquenessContract = SbtSemanticContract(
      events = uniquenessIds.map(id => event(id, BuildLogMessage, "uniqueness-shared")) :+
        event("uniqueness-boundary", BuildLogMessage, "uniqueness-boundary"),
      happensBefore = uniquenessIds.map(id => HappensBefore(id, "uniqueness-boundary")).toSet,
      matcherStateBudget = 5
    )
    val uniquenessFindings = collect(
      Vector(message("NORMAL", "uniqueness-boundary")) ++
        Vector.fill(2)(message("NORMAL", "uniqueness-shared")),
      uniquenessContract
    )
    Assert.assertTrue(uniquenessFindings.exists(finding =>
      finding.category == OrderingFailure && finding.disposition == Violation &&
        finding.semanticIdentity == "event-assignment-ordering"))
    Assert.assertTrue(uniquenessFindings.exists(finding =>
      finding.category == MatcherComplexityFailure && finding.disposition == Violation &&
        finding.semanticIdentity == "event-assignment-ordering-uniqueness"))
    Assert.assertEquals(2, uniquenessFindings.count(finding =>
      finding.category == OrderingFailure && finding.disposition == Blocked &&
        finding.semanticIdentity.startsWith("edge:uniqueness-shared-")))
    Assert.assertFalse(uniquenessFindings.exists(finding =>
      finding.category == SemanticCardinalityFailure && finding.disposition == Violation))
  }

  @Test def declaredLifecycleEdgesScopeIdenticalMembersWithoutHidingUnconstrainedAmbiguity(): Unit = {
    val ownership = exact("stable-block-flow")
    val contract = SbtSemanticContract(
      events = Vector(
        ExpectedSemanticEvent("first-open", BlockOpened, "name" -> exact("dependency")),
        ExpectedSemanticEvent("first-shared", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("shared")),
        ExpectedSemanticEvent("first-only", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("first-only")),
        ExpectedSemanticEvent("first-close", BlockClosed, "name" -> exact("dependency")),
        ExpectedSemanticEvent("second-open", BlockOpened, "name" -> exact("dependency")),
        ExpectedSemanticEvent("second-shared", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("shared")),
        ExpectedSemanticEvent("second-only", BuildLogMessage,
          "status" -> exact("NORMAL"), "text" -> exact("second-only")),
        ExpectedSemanticEvent("second-close", BlockClosed, "name" -> exact("dependency"))
      ),
      happensBefore = Set(HappensBefore("first-close", "second-open")),
      lifecycles = Vector(
        SemanticLifecycleRule.block(
          "first", "first-open", Seq("first-shared", "first-only"), "first-close", ownership),
        SemanticLifecycleRule.block(
          "second", "second-open", Seq("second-shared", "second-only"), "second-close", ownership)
      )
    )
    def boundary(kind: String): String =
      s"##teamcity[$kind name='dependency' flowId='stable-block-flow']"
    def message(text: String): String =
      s"##teamcity[message status='NORMAL' text='$text' flowId='stable-block-flow']"
    val valid = Vector(
      boundary("blockOpened"),
      message("shared"),
      message("first-only"),
      boundary("blockClosed"),
      boundary("blockOpened"),
      message("shared"),
      message("second-only"),
      boundary("blockClosed")
    )

    verify(valid, contract)
    verify(valid.updated(1, valid(2)).updated(2, valid(1))
      .updated(5, valid(6)).updated(6, valid(5)), contract)
    assertCategory(expectFailure(verify(
      valid.updated(2, valid(3)).updated(3, valid(2)), contract)), OrderingFailure)
    assertCategory(expectFailure(verify(
      valid.updated(3, valid(4)).updated(4, valid(3)), contract)), OrderingFailure)

    val ambiguous = SbtSemanticContract(Vector(
      event("first", BuildLogMessage, "shared"),
      event("second", BuildLogMessage, "shared")
    ))
    assertCategory(expectFailure(verify(Vector(
      "##teamcity[message status='NORMAL' text='shared']",
      "##teamcity[message status='NORMAL' text='shared']"
    ), ambiguous)), MatcherComplexityFailure)
  }

  @Test def globallySelectedOptionalGroupsDoNotRejectRequiredOrOtherGroupOverlap(): Unit = {
    val requiredOverlap = SbtSemanticContract(
      events = Vector(event("required", BuildLogMessage, "shared")),
      optionalGroups = Vector(OptionalSemanticEventGroup("optional", Vector(
        event("optional-head", BuildLogMessage, "shared"),
        event("optional-tail", BuildLogMessage, "tail")
      )))
    )
    verify(Vector("##teamcity[message status='NORMAL' text='shared']"), requiredOverlap)

    val overlappingGroups = SbtSemanticContract(
      events = Vector.empty,
      optionalGroups = Vector(
        OptionalSemanticEventGroup("first", Vector(
          event("first-head", BuildLogMessage, "shared"),
          event("first-tail", BuildLogMessage, "tail-a")
        )),
        OptionalSemanticEventGroup("second", Vector(
          event("second-head", BuildLogMessage, "shared"),
          event("second-tail", BuildLogMessage, "tail-b")
        ))
      )
    )
    verify(Vector(
      "##teamcity[message status='NORMAL' text='shared']",
      "##teamcity[message status='NORMAL' text='tail-a']"
    ), overlappingGroups)
  }

  @Test def optionalActivationUsesAllFeasibleAssignmentsRatherThanTheFirstTwo(): Unit = {
    def indistinguishableGroup(name: String): OptionalSemanticEventGroup = OptionalSemanticEventGroup(
      name,
      Vector(
        event(s"$name-first", BuildLogMessage, "shared"),
        event(s"$name-second", BuildLogMessage, "shared")
      ),
      happensBefore = Set(HappensBefore(s"$name-first", s"$name-second"))
    )
    val contract = SbtSemanticContract(
      events = Vector.empty,
      optionalGroups = Vector(indistinguishableGroup("earlier"), indistinguishableGroup("later"))
    )
    val findings = collect(Vector(
      "##teamcity[message status='NORMAL' text='shared']",
      "##teamcity[message status='NORMAL' text='shared']"
    ), contract)

    Seq("edge:earlier-first->earlier-second", "edge:later-first->later-second").foreach { identity =>
      Assert.assertTrue(s"Expected blocked activation-dependent edge $identity in $findings", findings.exists(finding =>
        finding.semanticIdentity == identity && finding.disposition == Blocked))
    }
  }

  @Test def optionalChoiceFeasibilityPrunesManyAbsentGroupsBeforeBudgetExhaustion(): Unit = {
    val groups = (1 to 20).map { index =>
      OptionalSemanticEventGroup(s"group-$index", Vector(event(s"event-$index", BuildLogMessage, s"value-$index")))
    }.toVector
    val contract = SbtSemanticContract(
      events = Vector.empty,
      optionalGroups = groups,
      matcherStateBudget = 100
    )

    verify(Vector.empty, contract)
    Assert.assertFalse(collect(Vector.empty, contract).exists(_.category == MatcherComplexityFailure))

    val matchingOutput = (1 to 10).map { index =>
      s"##teamcity[message status='NORMAL' text='value-$index']"
    }.toVector
    val defaultBudgetContract = SbtSemanticContract(events = Vector.empty, optionalGroups = groups)
    verify(matchingOutput, defaultBudgetContract)
    Assert.assertFalse(collect(matchingOutput, defaultBudgetContract).exists(_.category == MatcherComplexityFailure))
  }

  @Test def dependencyResourceContractsCorrelateOutcomeStatusAndTypedMetadata(): Unit = {
    val url = "https://repo.example.test/artifact.jar"
    def dependencyEvent(
      id: String,
      status: SemanticValuePattern,
      outcomes: Set[DependencyResourceOutcome]
    ): ExpectedSemanticEvent = ExpectedSemanticEvent(id, BuildLogMessage,
      "status" -> status,
      "flowId" -> exact("dependency"),
      "text" -> dependencyResource("root", "global", url, outcomes))

    val validCases = Vector(
      dependencyEvent("local", exact("NORMAL"), Set(DependencyResourceOutcome.LocalCacheHit)) ->
        s"##teamcity[message status='NORMAL' flowId='dependency' text='|[root / global|] local cache hit $url']",
      dependencyEvent("download", exact("NORMAL"), Set(DependencyResourceOutcome.Downloaded)) ->
        s"##teamcity[message status='NORMAL' flowId='dependency' text='|[root / global|] downloaded $url (size unknown, 0 ms)']",
      dependencyEvent("failure", exact("WARNING"), Set(DependencyResourceOutcome.FailedDownloadAttempt)) ->
        s"##teamcity[message status='WARNING' flowId='dependency' text='|[root / global|] failed download attempt $url (after 1.25 s)']"
    )
    validCases.foreach { case (expected, line) => verify(Vector(line), SbtSemanticContract(Vector(expected))) }

    val mixed = dependencyEvent("mixed", exact("NORMAL"), Set(
      DependencyResourceOutcome.LocalCacheHit,
      DependencyResourceOutcome.FailedDownloadAttempt
    ))
    val invalidContracts = Vector(
      mixed,
      dependencyEvent("wrong-status", exact("WARNING"), Set(DependencyResourceOutcome.Downloaded)),
      dependencyEvent("non-exact-status", unsignedDuration, Set(DependencyResourceOutcome.Downloaded))
    )
    invalidContracts.foreach { expected =>
      assertCategory(expectFailure(verify(Vector.empty, SbtSemanticContract(Vector(expected)))), GoldenSyntaxFailure)
    }
    val mixedFailure = expectFailure(verify(Vector.empty, SbtSemanticContract(Vector(mixed))))
    Assert.assertEquals(1, mixedFailure.failures.size)
    Assert.assertTrue(mixedFailure.failure.summary.contains("mixes NORMAL and WARNING"))
    Assert.assertFalse(mixedFailure.failure.summary.contains("must declare exact status"))

    val downloadContract = SbtSemanticContract(Vector(validCases(1)._1))
    Vector(
      validCases(1)._2.replace("root / global", "other / global"),
      validCases(1)._2.replace("root / global", "root / compile"),
      validCases(1)._2.replace(url, url + "?token=secret"),
      validCases(1)._2.replace("size unknown", "1 KB"),
      validCases(1)._2.replace("0 ms", "-1 ms")
    ).foreach { line => assertCategory(expectFailure(verify(Vector(line), downloadContract)), SemanticCardinalityFailure) }

    val failureContract = SbtSemanticContract(Vector(validCases(2)._1))
    assertCategory(expectFailure(verify(Vector(validCases(2)._2.replace("status='WARNING'", "status='NORMAL'")),
      failureContract)), SemanticCardinalityFailure)
  }

  @Test def compilerBridgePatternsAreValidOnlyAsOwnedAdjacentOptionalPairs(): Unit = {
    val bridgeFlow = SemanticBindingKey.flow("bridge-contract")
    val announcementEvent = ExpectedSemanticEvent("bridge-start", BuildLogMessage,
      "status" -> exact("NORMAL"), "text" -> compilerBridgeAnnouncement)
    val completionEvent = ExpectedSemanticEvent("bridge-end", BuildLogMessage,
      "status" -> exact("NORMAL"), "text" -> compilerBridgeCompletion)
    def group(
      events: Vector[ExpectedSemanticEvent] = Vector(announcementEvent, completionEvent),
      edges: Set[HappensBefore] = Set(HappensBefore("bridge-start", "bridge-end")),
      ownership: Option[SemanticValuePattern] = Some(bound(bridgeFlow))
    ): OptionalSemanticEventGroup = OptionalSemanticEventGroup("bridge-pair", events, edges, ownership)
    def contract(value: OptionalSemanticEventGroup): SbtSemanticContract =
      SbtSemanticContract(events = Vector.empty, optionalGroups = Vector(value))

    val invalidSemantic = Vector(
      SbtSemanticContract(Vector(announcementEvent)),
      contract(group(events = Vector(announcementEvent), edges = Set.empty)),
      contract(group(ownership = None)),
      contract(group(edges = Set.empty)),
      contract(group(events = Vector(
        announcementEvent,
        completionEvent.copy(attributes = completionEvent.attributes.updated(0, "status" -> exact("WARNING")))
      ))),
      contract(group(events = Vector(completionEvent, announcementEvent),
        edges = Set(HappensBefore("bridge-start", "bridge-end"))))
    )
    invalidSemantic.foreach(value => assertCategory(expectFailure(verify(Vector.empty, value)), GoldenSyntaxFailure))

    val invalidPlain = Vector(
      PlainOutputContract.Patterns(Vector(PlainOutputPattern.CompilerBridgeAnnouncement)),
      PlainOutputContract.Patterns(Vector(PlainOutputPattern.OptionalGroup(
        "single", Vector(PlainOutputPattern.CompilerBridgeAnnouncement)))),
      PlainOutputContract.Patterns(Vector(PlainOutputPattern.OptionalGroup(
        "reversed", Vector(PlainOutputPattern.CompilerBridgeCompletion, PlainOutputPattern.CompilerBridgeAnnouncement)))),
      PlainOutputContract.Patterns(Vector(PlainOutputPattern.OptionalGroup("nested", Vector(
        PlainOutputPattern.OptionalGroup("inner", Vector(
          PlainOutputPattern.CompilerBridgeAnnouncement,
          PlainOutputPattern.CompilerBridgeCompletion
        ))
      ))))
    )
    invalidPlain.foreach { plain =>
      assertCategory(expectFailure(verify(Vector.empty, SbtSemanticContract(Vector.empty, plainOutput = plain))),
        GoldenSyntaxFailure)
    }

    val announcement =
      "##teamcity[message status='NORMAL' text='|[info|] Non-compiled module |'compiler-bridge_2.13|' for Scala 2.13.18. Compiling...' flowId='bridge-flow']"
    val completion =
      "##teamcity[message status='NORMAL' text='|[info|]   Compilation completed in 2.5s.' flowId='bridge-flow']"
    val validContract = contract(group())
    verify(Vector(announcement, completion), validContract)
    assertCategory(expectFailure(verify(Vector(announcement, completion.replace("bridge-flow", "other-flow")),
      validContract)), FlowOwnershipFailure)
    assertCategory(expectFailure(verify(Vector(announcement.replace("Non-compiled", "Compiled"), completion),
      validContract)), SemanticCardinalityFailure)

    val withMiddle = validContract.copy(events = Vector(event("middle", BuildLogMessage, "middle")))
    val nonAdjacent = collect(Vector(
      announcement,
      "##teamcity[message status='NORMAL' text='middle']",
      completion
    ), withMiddle)
    Assert.assertTrue(nonAdjacent.exists(finding =>
      finding.semanticIdentity == "optional-group:bridge-pair:adjacency" && finding.disposition == Violation))
  }

  @Test def userFailureAllowsBoundedFrameworkPrefixAndTailAroundOneExactOrderedUserSequence(): Unit = {
    val cases = Vector(
      RecognizedTestFramework.ScalaTest -> "org.scalatest.Suite.run(Suite.scala:1)",
      RecognizedTestFramework.Specs2 -> "org.specs2.runner.SpecificationsFinder.run(SpecificationsFinder.scala:1)",
      RecognizedTestFramework.JUnit -> "org.junit.runners.ParentRunner.run(ParentRunner.java:1)"
    )
    cases.foreach { case (framework, frameworkFrame) =>
      val userFrames = Vector(
        "\tat fixture.Spec.prepare(Spec.scala:6)",
        "\tat fixture.Spec.fails(Spec.scala:7)"
      )
      val detailsPattern = framework match {
        case RecognizedTestFramework.ScalaTest => userFailure(
          "java.lang.AssertionError: boom",
          userFrames,
          framework,
          maximumFrameworkFrames = 2,
          allowedGeneratedOwners = Set("fixture.ParallelTest")
        )
        case _ => userFailure(
          "java.lang.AssertionError: boom",
          userFrames,
          framework,
          maximumFrameworkFrames = 2
        )
      }
      val contract = SbtSemanticContract(Vector(ExpectedSemanticEvent("failure", TestFailed,
        "name" -> exact("fixture.fails"),
        "details" -> detailsPattern,
        "flowId" -> exact("test"))))
      def failure(details: String): String =
        s"##teamcity[testFailed name='fixture.fails' details='${teamCityEscape(details)}' flowId='test']"
      val exactUser = userFrames.mkString("\n")
      val recognized = s"\tat $frameworkFrame"

      Vector(
        s"java.lang.AssertionError: boom\n$exactUser",
        s"java.lang.AssertionError: boom\n$recognized\n$exactUser",
        s"java.lang.AssertionError: boom\n$exactUser\n$recognized",
        s"java.lang.AssertionError: boom\n$recognized\n$exactUser\n\tat java.base/java.lang.reflect.Method.invoke(Method.java:2)",
        s"java.lang.AssertionError: boom\n$recognized\n$exactUser\n"
      ).foreach(details => verify(Vector(failure(details)), contract))
      if (framework == RecognizedTestFramework.ScalaTest) {
        verify(Vector(failure(
          s"java.lang.AssertionError: boom\n$exactUser\n" +
            "\tat fixture.ParallelTest.org$scalatest$ParallelTestExecution$$super$runTest(ParallelTest.scala:5)\n" +
            "\tat fixture.ParallelTest.runTest(ParallelTest.scala:5)"
        )), contract)
      }

      Vector(
        s"java.lang.AssertionError: changed\n$exactUser",
        s"java.lang.AssertionError: boom\n${userFrames.reverse.mkString("\n")}",
        s"java.lang.AssertionError: boom\n${userFrames.head}\n$exactUser",
        s"java.lang.AssertionError: boom\n$recognized\n$exactUser\n$recognized\n$recognized",
        s"java.lang.AssertionError: boom\n\n$exactUser",
        s"java.lang.AssertionError: boom\n$recognized\n\n$exactUser",
        s"java.lang.AssertionError: boom\n$exactUser\n\n",
        s"java.lang.AssertionError: boom\n$exactUser\n\n\n",
        s"java.lang.AssertionError: boom\n\tat com.foreign.Runner.run(Runner.scala:1)\n$exactUser",
        s"java.lang.AssertionError: boom\n$exactUser\n\tat com.foreign.Runner.run(Runner.scala:1)",
        s"java.lang.AssertionError: boom\n$exactUser\n\tat com.foreign.ForeignTest.run(ForeignTest.scala:1)",
        s"java.lang.AssertionError: boom\n$exactUser\n" +
          "\tat com.foreign.ForeignTest.org$scalatest$ParallelTestExecution$$super$runTest(ForeignTest.scala:1)\n" +
          "\tat com.foreign.ForeignTest.runTest(ForeignTest.scala:1)",
        s"java.lang.AssertionError: boom\n$exactUser\n" +
          "\tat tests.ForeignTest.org$scalatest$ParallelTestExecution$$super$runTest(ForeignTest.scala:1)\n" +
          "\tat tests.ForeignTest.runTest(ForeignTest.scala:1)",
        s"java.lang.AssertionError: boom\nCaused by: java.lang.IllegalStateException: hidden\n$exactUser",
        s"java.lang.AssertionError: boom\n$exactUser\nCaused by: java.lang.IllegalStateException: hidden"
      ).foreach(details =>
        assertCategory(expectFailure(verify(Vector(failure(details)), contract)), SemanticCardinalityFailure))
    }
  }

  @Test def userFailureContractRejectsInvalidDeclarations(): Unit = {
    val invalidPatterns = Vector(
      userFailure(
        "java.lang.AssertionError: boom",
        Seq("\tat fixture.Spec.fails(Spec.scala:7)", "\tat fixture.Spec.fails(Spec.scala:7)"),
        RecognizedTestFramework.ScalaTest
      ),
      userFailure(
        "java.lang.AssertionError: boom",
        Seq("\tat fixture.Spec.fails(Spec.scala:7)"),
        RecognizedTestFramework.ScalaTest,
        allowedGeneratedOwners = Set("", "fixture..ParallelTest")
      ),
      userFailure(
        "java.lang.AssertionError: boom",
        Seq("\tat fixture.Spec.fails(Spec.scala:7)"),
        RecognizedTestFramework.JUnit,
        allowedGeneratedOwners = Set("fixture.ParallelTest")
      )
    )

    invalidPatterns.foreach { pattern =>
      val contract = SbtSemanticContract(Vector(ExpectedSemanticEvent("failure", TestFailed,
        "name" -> exact("fixture.fails"),
        "details" -> pattern,
        "flowId" -> exact("test"))))
      assertCategory(expectFailure(verify(Vector.empty, contract)), GoldenSyntaxFailure)
    }
  }

  @Test def taskSummaryPatternsAcceptBothFiniteBranchesAndRejectNearMisses(): Unit = {
    val contract = SbtSemanticContract(
      events = Vector.empty,
      plainOutput = PlainOutputContract.Patterns(Vector(PlainOutputPattern.SbtTaskSummary))
    )
    Vector(
      "[success] Total time: 1.25 s, completed Aug 26, 2026, 12:00:00 PM",
      "[error] elapsed time: 0.5 s, cache 42%, 3 tasks"
    ).foreach(line => verify(Vector(line), contract))
    Vector(
      "[warn] Total time: 1.25 s",
      "[success] Total time: -1 s",
      "[error] elapsed time: 1 s, cache -1%, 3 tasks",
      "[error] elapsed time: 1 s, cache 50%"
    ).foreach(line => assertCategory(expectFailure(verify(Vector(line), contract)), PlainOutputFailure))
  }

  @Test def compilerPlainPatternsKeepTargetsAndParsedInspectionDiagnosticsStrict(): Unit = {
    import CompilerDiagnosticLevel.*

    val workspace = SemanticBindingKey.path("plain-workspace")
    val contract = SbtSemanticContract(
      events = Vector(ExpectedSemanticEvent("problem", Inspection,
        "SEVERITY" -> exact("ERROR"),
        "line" -> exact("2"),
        "typeId" -> exact("SbtCompileProblem"),
        "message" -> exact("invalid literal number"),
        "file" -> exact("/work/src/main/scala/Broken.scala"))),
      plainOutput = PlainOutputContract.Patterns(Vector(
        PlainOutputPattern.BeforeServiceMessages(
          PlainOutputPattern.CompilerCompileInfo(workspace, "/target/scala-2.13/classes")),
        PlainOutputPattern.AfterServiceMessages(
          PlainOutputPattern.CompilerInspectionDiagnostic(
            workspace, "/src/main/scala/Broken.scala", Error, 35))
      ))
    )
    val inspection =
      "##teamcity[inspection SEVERITY='ERROR' line='2' typeId='SbtCompileProblem' " +
        "message='invalid literal number' file='/work/src/main/scala/Broken.scala']"
    val valid = Vector(
      "[info] compiling 1 Scala source to /work/target/scala-2.13/classes ...",
      inspection,
      "[error] /work/src/main/scala/Broken.scala:2:35: invalid literal number"
    )

    verify(valid, contract)
    val normalizedContract = contract.copy(events = contract.events.map { event =>
      if (event.id.value == "problem") event.copy(attributes = event.attributes.map {
        case ("file", _) => "file" -> exact("${BASE}/src/main/scala/Broken.scala")
        case attribute => attribute
      }) else event
    })
    val normalized = valid.updated(
      1,
      inspection.replace("/work/src/main/scala/Broken.scala", "${BASE}/src/main/scala/Broken.scala")
    )
    verify(normalized, normalizedContract)
    Vector(
      valid.updated(0, "[info] compiling 2 Scala sources to /work/target/scala-2.13/classes ..."),
      valid.updated(0, "[info] compiling 1 Scala source to /work/target/scala-3/classes ..."),
      valid.updated(2, "[error] /other/src/main/scala/Broken.scala:2:35: invalid literal number"),
      valid.updated(2, "[error] /work/src/main/scala/Broken.scala:2:36: invalid literal number"),
      valid.updated(2, "[error] /work/src/main/scala/Broken.scala:2:35: different")
    ).foreach(lines => assertCategory(expectFailure(verify(lines, contract)), PlainOutputFailure))
    val splitWorkspace = normalized.updated(
      2,
      "[error] /other/src/main/scala/Broken.scala:2:35: invalid literal number"
    )
    assertCategory(expectFailure(verify(splitWorkspace, normalizedContract)), PlainOutputFailure)

    val secondProblem = ExpectedSemanticEvent("other-problem", Inspection,
      "SEVERITY" -> exact("ERROR"),
      "line" -> exact("9"),
      "typeId" -> exact("SbtCompileProblem"),
      "message" -> exact("other problem"),
      "file" -> exact("${BASE}/src/main/scala/Other.scala"))
    val withSecondProblem = normalizedContract.copy(events = normalizedContract.events :+ secondProblem)
    val otherInspection =
      "##teamcity[inspection SEVERITY='ERROR' line='9' typeId='SbtCompileProblem' " +
        "message='other problem' file='${BASE}/src/main/scala/Other.scala']"
    verify(normalized.patch(2, Vector(otherInspection), 0), withSecondProblem)

    val warningContract = SbtSemanticContract(
      events = Vector(ExpectedSemanticEvent("warning", Inspection,
        "SEVERITY" -> exact("WARNING"),
        "line" -> exact("7"),
        "typeId" -> exact("SbtCompileProblem"),
        "message" -> exact("unused value"),
        "file" -> exact("${BASE}/src/main/scala/Warning.scala"))),
      plainOutput = PlainOutputContract.Patterns(Vector(
        PlainOutputPattern.CompilerInspectionDiagnostic(
          workspace, "/src/main/scala/Warning.scala", Warning, 12)
      ))
    )
    verify(Vector(
      "##teamcity[inspection SEVERITY='WARNING' line='7' typeId='SbtCompileProblem' " +
        "message='unused value' file='${BASE}/src/main/scala/Warning.scala']",
      "[warn] /work/src/main/scala/Warning.scala:7:12: unused value"
    ), warningContract)
  }

  @Test def rawCompilerBridgeCanDeclareServiceMessagePlacementAsOneOptionalGroup(): Unit = {
    val contract = SbtSemanticContract(
      events = Vector(ExpectedSemanticEvent("problem-type", InspectionType,
        "id" -> exact("SbtCompileProblem"))),
      plainOutput = PlainOutputContract.Patterns(Vector(
        PlainOutputPattern.OptionalGroup("raw-bridge-before-inspection", Vector(
          PlainOutputPattern.BeforeServiceMessages(PlainOutputPattern.CompilerBridgeAnnouncement),
          PlainOutputPattern.BeforeServiceMessages(PlainOutputPattern.CompilerBridgeCompletion)
        ))
      ))
    )
    val announcement = "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.18. Compiling..."
    val completion = "[info]   Compilation completed in 1.25s."
    val inspectionType = "##teamcity[inspectionType id='SbtCompileProblem']"

    verify(Vector(inspectionType), contract)
    verify(Vector(announcement, completion, inspectionType), contract)
    assertCategory(expectFailure(verify(Vector(announcement, inspectionType, completion), contract)), PlainOutputFailure)
  }

  @Test def structuredSbtDebugClassifiersAcceptEveryFiniteCategory(): Unit = {
    val representatives = Vector(
      StructuredSbtDebugKind.DependencyCheck -> "[debug] not up to date. inChanged = true, force = false",
      StructuredSbtDebugKind.DependencyUpdate -> "[debug] Updating root...",
      StructuredSbtDebugKind.DependencyDone -> "[debug] Done updating root",
      StructuredSbtDebugKind.IncrementalHeader -> "[debug] [zinc] IncrementalCompile -----------",
      StructuredSbtDebugKind.IncrementalCompile -> "[debug] IncrementalCompile.incrementalCompile",
      StructuredSbtDebugKind.PreviousStamps ->
        "[debug] previous = Stamps for: 0 products, 0 sources, 0 libraries",
      StructuredSbtDebugKind.CurrentSources -> "[debug] current source = Set()",
      StructuredSbtDebugKind.InitialChanges -> "[debug] > initialChanges = InitialChanges(...)",
      StructuredSbtDebugKind.FullCompilation -> "[debug] Full compilation, no sources in previous analysis.",
      StructuredSbtDebugKind.InvalidatedSources -> "[debug] all 1 sources are invalidated",
      StructuredSbtDebugKind.InitialIncludedNodes -> "[debug] Initial set of included nodes: source",
      StructuredSbtDebugKind.RecompileAllSources ->
        "[debug] Recompiling all sources: number of invalidated sources > 50.0% of all sources",
      StructuredSbtDebugKind.CompilationCycle -> "[debug] compilation cycle 1",
      StructuredSbtDebugKind.CompilerBridgeRetrieval ->
        "[debug] Getting org.scala-sbt:compiler-bridge_2.13:1.12.0:compile for Scala 2.13.16",
      StructuredSbtDebugKind.CachedCompiler ->
        "[debug] [zinc] Running cached compiler abc123 for Scala compiler version 2.13.18",
      StructuredSbtDebugKind.CompilerArguments ->
        "[debug] [zinc] The Scala compiler is invoked with:\n[debug] \t-classpath\n[debug] \t/tmp/classes",
      StructuredSbtDebugKind.CompilationFailed -> "[debug] Compilation failed (CompilerInterface)",
      StructuredSbtDebugKind.CreatedClassFileManager ->
        "[debug] Created transactional ClassFileManager with tempDir = /tmp/classes.bak",
      StructuredSbtDebugKind.AboutToDeleteClassFiles -> "[debug] About to delete class files:\n[debug] ",
      StructuredSbtDebugKind.BackupClassFiles -> "[debug] We backup class files:\n[debug] ",
      StructuredSbtDebugKind.RollbackClassFiles -> "[debug] Rolling back changes to class files.",
      StructuredSbtDebugKind.RemoveGeneratedClasses -> "[debug] Removing generated classes:\n[debug] ",
      StructuredSbtDebugKind.RestoreClassFiles -> "[debug] Restoring class files: \n[debug] ",
      StructuredSbtDebugKind.RemoveTemporaryDirectory ->
        "[debug] Removing the temporary directory used for backing up class files: /tmp/classes.bak",
      StructuredSbtDebugKind.WroteProducts -> "[debug] wrote /tmp/classes"
    )
    Assert.assertEquals(StructuredSbtDebugKind.values.toSet, representatives.map(_._1).toSet)

    representatives.foreach { case (kind, text) =>
      val contract = SbtSemanticContract(Vector(ExpectedSemanticEvent("debug", BuildLogMessage,
        "status" -> exact("NORMAL"), "text" -> structuredSbtDebug(kind))))
      verify(Vector(message("NORMAL", text)), contract)
    }
    Vector(
      StructuredSbtDebugKind.DependencyUpdate -> "[debug] Updating ...",
      StructuredSbtDebugKind.DependencyDone -> "[debug] Done updating ",
      StructuredSbtDebugKind.RecompileAllSources ->
        "[debug] Recompiling all sources: number of invalidated sources > 50.0 percent of all sources",
      StructuredSbtDebugKind.CompilerBridgeRetrieval ->
        "[debug] Returning already retrieved and compiled bridge: /cache/scala3-sbt-bridge-3.8.4.jar.",
      StructuredSbtDebugKind.CachedCompiler ->
        "[debug] [zinc] Running cached compiler abc123 for Scala Compiler version 3.8.4",
      StructuredSbtDebugKind.CompilationFailed -> "[debug] Compilation failed"
    ).foreach { case (kind, text) =>
      val contract = SbtSemanticContract(Vector(ExpectedSemanticEvent("debug", BuildLogMessage,
        "status" -> exact("NORMAL"), "text" -> structuredSbtDebug(kind))))
      verify(Vector(message("NORMAL", text)), contract)
    }
  }

  @Test def structuredSbtDebugClassifiersRejectUnknownAndNearMissSentences(): Unit = {
    val nearMisses = Vector(
      StructuredSbtDebugKind.DependencyCheck -> "[debug] not up to date. unknown state",
      StructuredSbtDebugKind.CurrentSources -> "[debug] current source = Set(unclosed",
      StructuredSbtDebugKind.FullCompilation -> "[debug] Full compilation unexpectedly succeeded",
      StructuredSbtDebugKind.CompilerArguments -> "[debug] [zinc] The Scala compiler is invoked with:",
      StructuredSbtDebugKind.CompilationFailed -> "[debug] Compilation failed but recovered",
      StructuredSbtDebugKind.CreatedClassFileManager -> "[debug] Created transactional ClassFileManager",
      StructuredSbtDebugKind.WroteProducts -> "[debug] wrote definitely-not-an-output-directory"
    )
    nearMisses.foreach { case (kind, text) =>
      val contract = SbtSemanticContract(Vector(ExpectedSemanticEvent("debug", BuildLogMessage,
        "status" -> exact("NORMAL"), "text" -> structuredSbtDebug(kind))))
      assertCategory(expectFailure(verify(Vector(message("NORMAL", text)), contract)), SemanticCardinalityFailure)
    }
  }

  @Test def structuredSbtDebugRequiresNormalBuildLogText(): Unit = {
    val pattern = structuredSbtDebug(StructuredSbtDebugKind.FullCompilation)
    val invalid = Vector(
      ExpectedSemanticEvent("missing-status", BuildLogMessage, "text" -> pattern),
      ExpectedSemanticEvent("wrong-status", BuildLogMessage,
        "status" -> exact("WARNING"), "text" -> pattern),
      ExpectedSemanticEvent("wrong-attribute", BuildLogMessage,
        "status" -> exact("NORMAL"), "message" -> pattern),
      ExpectedSemanticEvent("wrong-kind", Inspection,
        "status" -> exact("NORMAL"), "text" -> pattern)
    )
    invalid.foreach { expected =>
      assertCategory(expectFailure(verify(Vector.empty, SbtSemanticContract(Vector(expected)))), GoldenSyntaxFailure)
    }
  }

  @Test def rawSbtDebugPatternsEnforceCategoryOrderAndExactCardinality(): Unit = {
    val contract = SbtSemanticContract(
      events = Vector.empty,
      plainOutput = PlainOutputContract.Patterns(Vector(
        PlainOutputPattern.SbtDebug(RawSbtDebugKind.CommandExecution),
        PlainOutputPattern.SbtDebug(RawSbtDebugKind.TaskEvaluation),
        PlainOutputPattern.SbtDebug(RawSbtDebugKind.TaskRun)
      ))
    )
    val valid = Vector(
      "[debug] > Exec(compile, None, None)",
      "[debug] Evaluating tasks: Compile / compile",
      "[debug] Running task... Cancel: Null, check cycles: false, forcegc: true"
    )
    verify(valid, contract)
    Vector(
      valid.updated(0, "[debug] Executing compile"),
      valid.updated(0, "[debug] > Exec(unclosed"),
      valid.updated(1, "[debug] Evaluating tasks:"),
      valid.updated(2, "[debug] Running task... unexpected same-prefix text"),
      Vector(valid(1), valid(0), valid(2)),
      valid.dropRight(1),
      valid :+ valid.last
    ).foreach { lines =>
      assertCategory(expectFailure(verify(lines, contract)), PlainOutputFailure)
    }
  }

  @Test def optionalAndLifecycleValidatorsRejectInvalidStructureDeterministically(): Unit = {
    val pathOwnership = SemanticBindingKey.path("not-ownership")
    val invalid = Vector(
      SbtSemanticContract(Vector.empty, optionalGroups = Vector(
        OptionalSemanticEventGroup("empty", Vector.empty))),
      SbtSemanticContract(Vector.empty, optionalGroups = Vector(
        OptionalSemanticEventGroup("same", Vector(event("one", BuildLogMessage, "one"))),
        OptionalSemanticEventGroup("same", Vector(event("two", BuildLogMessage, "two"))))),
      SbtSemanticContract(Vector.empty, optionalGroups = Vector(
        OptionalSemanticEventGroup("bad-edge", Vector(event("one", BuildLogMessage, "one")),
          happensBefore = Set(HappensBefore("one", "missing"))))),
      SbtSemanticContract(Vector.empty, optionalGroups = Vector(
        OptionalSemanticEventGroup("bad-owner", Vector(event("one", BuildLogMessage, "one")),
          ownership = Some(bound(pathOwnership)))))
    )
    invalid.foreach(contract => assertCategory(expectFailure(verify(Vector.empty, contract)), GoldenSyntaxFailure))

    val invalidPlain = Vector(
      PlainOutputContract.Patterns(Vector(
        PlainOutputPattern.OptionalGroup("empty", Vector.empty))),
      PlainOutputContract.Patterns(Vector(
        PlainOutputPattern.OptionalGroup("same", Vector(PlainOutputPattern.Exact("first"))),
        PlainOutputPattern.OptionalGroup("same", Vector(PlainOutputPattern.Exact("second")))))
    )
    invalidPlain.foreach { plain =>
      assertCategory(expectFailure(verify(Vector.empty, SbtSemanticContract(Vector.empty, plainOutput = plain))),
        GoldenSyntaxFailure)
    }

    val duplicateLifecycle = compilationContract().copy(
      lifecycles = compilationContract().lifecycles ++ compilationContract().lifecycles.take(1)
    )
    val duplicateFailure = expectFailure(verify(Vector.empty, duplicateLifecycle))
    Assert.assertTrue(duplicateFailure.failures.exists(_.summary.contains("Duplicate lifecycle name 'compile-a'")))

    val cyclic = SbtSemanticContract(
      events = Vector(event("a", BuildLogMessage, "a"), event("b", BuildLogMessage, "b")),
      happensBefore = Set(HappensBefore("b", "a"), HappensBefore("a", "b"))
    )
    val cycleFailure = expectFailure(verify(Vector.empty, cyclic))
    Assert.assertTrue(cycleFailure.failures.exists(_.summary.contains("a -> b -> a")))
  }

  @Test def compoundMutationReportsIndependentViolationsAndBlockedDependenciesTogether(): Unit = {
    val flowC = SemanticBindingKey.flow("compile-c")
    val inspection = ExpectedSemanticEvent("inspection", Inspection,
      "typeId" -> exact("scala"), "message" -> exact("stable"), "severity" -> exact("WARNING"))
    val contract = compilationContract().copy(
      events = compilationContract().events ++ Vector(
        ExpectedSemanticEvent("compile-c-start", CompilationStarted, "compiler" -> exact("compiler-c")),
        ExpectedSemanticEvent("compile-c-finish", CompilationFinished, "compiler" -> exact("compiler-c")),
        inspection
      ),
      lifecycles = compilationContract().lifecycles :+
        SemanticLifecycleRule.compilation("compile-c", "compile-c-start", Seq.empty, "compile-c-finish", flowC),
      distinctBindings = compilationContract().distinctBindings ++ Set(
        DistinctSemanticBindings(flowA, flowC), DistinctSemanticBindings(flowB, flowC)),
      processResult = ProcessResultContract.Success
    )
    val mutated = Vector(
      compilationStarted("compiler-a", "flow-a"),
      compilationFinished("compiler-a", "flow-a"),
      buildMessage("flow-a", "body-b"),
      compilationStarted("compiler-b", "flow-b"),
      compilationFinished("compiler-b", "flow-b"),
      compilationStarted("compiler-c", "flow-c"),
      "##teamcity[inspection typeId='scala' message='changed' severity='ERROR']",
      "##teamcity[fixtureUnexpected value='extra']",
      "unexpected plain output"
    )
    val error = expectFailure(SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 7))
    val findings = error.failures

    Seq(SemanticCardinalityFailure, FlowOwnershipFailure, LifecycleFailure, OrderingFailure,
      PlainOutputFailure, ProcessResultFailure).foreach { category =>
      Assert.assertTrue(s"Missing $category in $findings", findings.exists(_.category == category))
    }
    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "event:inspection" && finding.disposition == Violation))
    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "kind:fixtureUnexpected" && finding.disposition == Violation))
    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "lifecycle:compile-c" && finding.disposition == Violation))
    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity.contains("compile-a-message") && finding.disposition == Blocked))
    Assert.assertTrue(findings.exists(finding =>
      finding.semanticIdentity == "edge:compile-b-start->compile-b-message" && finding.disposition == Violation))
    Seq("SemanticCardinalityFailure", "FlowOwnershipFailure", "LifecycleFailure", "OrderingFailure",
      "PlainOutputFailure", "ProcessResultFailure", "[Violation]", "[Blocked]", "event:inspection",
      "kind:fixtureUnexpected", "lifecycle:compile-c", "compile-a-message",
      "Required happens-before edge 'compile-b-start' -> 'compile-b-message' is reversed.").foreach { text =>
      Assert.assertTrue(s"Missing '$text' in rendered compound failure", error.getMessage.contains(text))
    }
  }

  private def nonOwnershipCase(
    key: SemanticBindingKey,
    output: Vector[String],
    events: Vector[ExpectedSemanticEvent]
  ): (SemanticBindingKey, Vector[String], SbtSemanticContract) =
    (key, output, SbtSemanticContract(events = events))

  private def compilationContract(): SbtSemanticContract = SbtSemanticContract(
    events = Vector(
      ExpectedSemanticEvent("compile-a-start", CompilationStarted, "compiler" -> exact("compiler-a")),
      ExpectedSemanticEvent("compile-a-message", BuildLogMessage,
        "status" -> exact("NORMAL"), "text" -> exact("body-a")),
      ExpectedSemanticEvent("compile-a-finish", CompilationFinished, "compiler" -> exact("compiler-a")),
      ExpectedSemanticEvent("compile-b-start", CompilationStarted, "compiler" -> exact("compiler-b")),
      ExpectedSemanticEvent("compile-b-message", BuildLogMessage,
        "status" -> exact("NORMAL"), "text" -> exact("body-b")),
      ExpectedSemanticEvent("compile-b-finish", CompilationFinished, "compiler" -> exact("compiler-b"))
    ),
    lifecycles = Vector(
      SemanticLifecycleRule.compilation(
        "compile-a", "compile-a-start", Seq("compile-a-message"), "compile-a-finish", flowA),
      SemanticLifecycleRule.compilation(
        "compile-b", "compile-b-start", Seq("compile-b-message"), "compile-b-finish", flowB)
    ),
    distinctBindings = Set(DistinctSemanticBindings(flowA, flowB))
  )

  private def validCompilationOutput(): Vector[String] = Vector(
    compilationStarted("compiler-a", "flow-a"),
    buildMessage("flow-a", "body-a"),
    compilationFinished("compiler-a", "flow-a"),
    compilationStarted("compiler-b", "flow-b"),
    buildMessage("flow-b", "body-b"),
    compilationFinished("compiler-b", "flow-b")
  )

  private def event(id: String, kind: ObservedServiceMessageKind, text: String): ExpectedSemanticEvent =
    ExpectedSemanticEvent(id, kind, "status" -> exact("NORMAL"), "text" -> exact(text))

  private def compilationStarted(compiler: String, flow: String): String =
    s"##teamcity[compilationStarted compiler='$compiler' flowId='$flow']"

  private def compilationFinished(compiler: String, flow: String): String =
    s"##teamcity[compilationFinished compiler='$compiler' flowId='$flow']"

  private def buildMessage(flow: String, text: String): String =
    s"##teamcity[message status='NORMAL' flowId='$flow' text='$text']"

  private def message(status: String, text: String): String =
    s"##teamcity[message status='$status' text='${teamCityEscape(text)}']"

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")

  private def verify(lines: Vector[String], contract: SbtSemanticContract): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      contract,
      exitCode = 0,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(lines: Vector[String], contract: SbtSemanticContract): Vector[SbtSemanticFailure] =
    SbtSemanticOutputVerifier.collect(
      lines,
      contract,
      exitCode = 0,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def expectFailure(action: => Unit): SbtSemanticVerificationException = try {
    action
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def assertCategory(
    failure: SbtSemanticVerificationException,
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category in ${failure.failures.map(_.category)}",
    failure.failures.exists(_.category == category)
  )

  private def interleavings(left: Vector[String], right: Vector[String]): Vector[Vector[String]] =
    if (left.isEmpty) Vector(right)
    else if (right.isEmpty) Vector(left)
    else {
      interleavings(left.tail, right).map(left.head +: _) ++
        interleavings(left, right.tail).map(right.head +: _)
    }
}
