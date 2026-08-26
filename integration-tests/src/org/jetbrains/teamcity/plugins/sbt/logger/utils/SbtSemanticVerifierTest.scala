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
