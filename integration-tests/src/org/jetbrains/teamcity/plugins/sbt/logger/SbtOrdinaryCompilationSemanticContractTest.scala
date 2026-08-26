package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtOrdinaryCompilationSemanticContractTest {
  import PlainOutputContract.*
  import PlainOutputPattern.*
  import ProcessResultContract.*
  import SemanticValuePattern.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private val Profiles = Vector(
    "sbt-1.4-jdk8",
    "sbt-1-jdk8",
    "sbt-1-jdk17",
    "sbt-2-jdk17"
  )

  private val Selections = Vector(
    "compilation-success" -> SbtOrdinaryCompilationSemanticContracts.Success,
    "compilation-failure" -> SbtOrdinaryCompilationSemanticContracts.Failure,
    "compilation-warnings" -> SbtOrdinaryCompilationSemanticContracts.Warnings,
    "compilation-subproject" -> SbtOrdinaryCompilationSemanticContracts.Subproject,
    "project-no-build-file" -> SbtOrdinaryCompilationSemanticContracts.NoBuildFile,
    "compile-incremental" -> SbtOrdinaryCompilationSemanticContracts.Incremental,
    "compilation-up-to-date" -> SbtOrdinaryCompilationSemanticContracts.UpToDate,
    "compile-inputs" -> SbtOrdinaryCompilationSemanticContracts.Inputs
  )

  private val TaskSummary = "[success] Total time: 1 s"
  private val CompilerFlow = "101:compile:compiler"

  @Test def selectionMatrixKeepsOnlyTheSbt2SuccessCanaryExact(): Unit = {
    Selections.foreach { case (scenarioId, selection) =>
      Profiles.foreach { profile =>
        val mode = selection.forRuntimeProfile(profile)
        val expectedExact = scenarioId == "compilation-success" && isSbt2(profile)
        Assert.assertEquals(
          s"Exact selection for $scenarioId on $profile",
          expectedExact,
          mode == ExactTranscript
        )
        if (!expectedExact) {
          Assert.assertTrue(s"Expected semantic selection for $scenarioId on $profile: $mode",
            mode.isInstanceOf[Semantic])
        }
      }
    }
  }

  @Test def futureProfilesStayExactUntilExplicitlyMigrated(): Unit = {
    Selections.foreach { case (scenarioId, selection) =>
      Assert.assertEquals(
        s"Unmigrated runtime selection for $scenarioId",
        ExactTranscript,
        selection.forRuntimeProfile("sbt-future-jdk21")
      )
    }
  }

  @Test def everySemanticCoordinateAcceptsItsValidSyntheticTranscript(): Unit = {
    Selections.foreach { case (scenarioId, selection) =>
      Profiles.foreach { profile =>
        selection.forRuntimeProfile(profile) match {
          case Semantic(contract) => verify(validTranscript(scenarioId, profile), contract)
          case ExactTranscript =>
            // The runtime remains exact, but the same profile shape stays unit-tested semantically.
            verify(validTranscript(scenarioId, profile), contractFor(scenarioId, profile))
          case other => Assert.fail(s"Unexpected verification mode for $scenarioId on $profile: $other")
        }
      }
    }
  }

  @Test def profilesDeclareExactEventLifecycleSummaryCountsAndOutputSuffixes(): Unit = {
    Selections.foreach { case (scenarioId, _) =>
      Profiles.foreach { profile =>
        val contract = contractFor(scenarioId, profile)
        Assert.assertEquals(s"Process result for $scenarioId on $profile", DelegatedToHarness, contract.processResult)
        Assert.assertEquals(
          s"Event count for $scenarioId on $profile",
          expectedEventCount(scenarioId, profile),
          contract.events.map(_.multiplicity).sum
        )
        Assert.assertEquals(
          s"Lifecycle count for $scenarioId on $profile",
          expectedLifecycleCount(scenarioId, profile),
          contract.lifecycles.size
        )
        Assert.assertEquals(
          s"Plain output for $scenarioId on $profile",
          summaryContract(expectedSummaryCount(scenarioId, profile)),
          contract.plainOutput
        )
      }
    }

    Vector("compilation-success", "compile-incremental", "compilation-up-to-date").foreach { scenarioId =>
      Profiles.foreach { profile =>
        val expectedSuffix = if (isSbt2(profile))
          s"/target/out/jvm/scala-2.12.20/$scenarioId/classes ..."
        else "/target/scala-2.13/classes ..."
        Assert.assertEquals(expectedSuffix, outputSuffix(contractFor(scenarioId, profile), s"$scenarioId-info"))
      }
    }
    Profiles.foreach { profile =>
      val expectedSuffix = if (isSbt2(profile))
        "/target/out/jvm/scala-2.12.20/backend/classes ..."
      else "/backend/target/scala-2.13/classes ..."
      Assert.assertEquals(expectedSuffix,
        outputSuffix(contractFor("compilation-subproject", profile), "compilation-subproject-info"))
    }
  }

  @Test def emptyContractsRejectAnyInventedServiceOrPlainOutput(): Unit = {
    Profiles.filterNot(isSbt2).foreach { profile =>
      Vector("project-no-build-file", "compile-inputs").foreach { scenarioId =>
        val contract = contractFor(scenarioId, profile)
        verify(Vector.empty, contract)
        assertViolation(collect(Vector(message(CompilerFlow, "NORMAL", "unexpected")), contract),
          SemanticCardinalityFailure)
        assertViolation(collect(Vector("unexpected plain output"), contract), PlainOutputFailure)
      }
    }

    val inputs = contractFor("compile-inputs", "sbt-2-jdk17")
    verify(Vector(TaskSummary), inputs)
    assertViolation(collect(Vector.empty, inputs), PlainOutputFailure)
    assertViolation(collect(Vector(TaskSummary, message(CompilerFlow, "NORMAL", "unexpected")), inputs),
      SemanticCardinalityFailure)

    val noBuild = contractFor("project-no-build-file", "sbt-2-jdk17")
    val noBuildLines = validTranscript("project-no-build-file", "sbt-2-jdk17")
    assertViolation(collect(noBuildLines.tail, noBuild), SemanticCardinalityFailure)
    assertViolation(collect(noBuildLines :+ TaskSummary, noBuild), PlainOutputFailure)
  }

  @Test def subprojectContractFocusesBackendCompilerLifecyclePathAndOwnership(): Unit = {
    Profiles.foreach { profile =>
      val contract = contractFor("compilation-subproject", profile)
      val valid = validTranscript("compilation-subproject", profile)
      verify(valid, contract)
      Assert.assertEquals("Scala compiler [backend]",
        exactAttribute(contract, "compilation-subproject-start", "compiler"))
      Assert.assertEquals(1, contract.lifecycles.size)

      val wrongCompiler = updateFirst(valid,
        line => line.startsWith("##teamcity[compilationStarted") && line.contains("Scala compiler |[backend|]"),
        _.replace("Scala compiler |[backend|]", "Scala compiler |[root|]"))
      val wrongPath = updateFirst(valid, _.contains("compiling 1 Scala source"),
        _.replace("/backend/", "/frontend/"))
      val nonNumericOwner = valid.map(_.replace("101:compile:compiler", "backend:compile:compiler"))

      Vector(wrongCompiler, wrongPath, nonNumericOwner).foreach { mutated =>
        assertViolation(collect(mutated, contract), SemanticCardinalityFailure)
      }
    }
  }

  @Test def compilerBridgeIsOptionalCompleteAdjacentOwnedAndInsideCompilation(): Unit = {
    Profiles.foreach { profile =>
      val contract = contractFor("compilation-success", profile)
      val warm = validTranscript("compilation-success", profile)
      val cold = ordinaryCompilation("compilation-success", profile, includeBridge = true)
      verify(warm, contract)
      verify(cold, contract)

      val announcement = uniqueIndex(cold, _.contains("Non-compiled module"), "bridge announcement")
      val completion = uniqueIndex(cold, _.contains("Compilation completed in"), "bridge completion")
      val done = uniqueIndex(cold, _.contains("done compiling"), "done message")
      val incomplete = cold.patch(completion, Vector.empty, 1)
      assertViolation(collect(incomplete, contract), SemanticCardinalityFailure)

      val wrongOwner = cold.updated(announcement,
        cold(announcement).replace(CompilerFlow, "102:compile:compiler"))
      assertViolation(collect(wrongOwner, contract), FlowOwnershipFailure)

      val nonAdjacent = cold.updated(completion, cold(done)).updated(done, cold(completion))
      assertViolation(collect(nonAdjacent, contract), SemanticCardinalityFailure, "event-assignment")

      val bridgePair = cold.slice(announcement, completion + 1)
      val outside = bridgePair ++ cold.patch(announcement, Vector.empty, bridgePair.size)
      assertViolation(collect(outside, contract), OrderingFailure,
        "edge:compilation-success-info->compilation-success-bridge-announcement")
    }
  }

  @Test def failureKeepsProfileCapitalizationSharedPathAndUnflowedProtocolEventsExact(): Unit = {
    Profiles.foreach { profile =>
      val contract = contractFor("compilation-failure", profile)
      val valid = validTranscript("compilation-failure", profile)
      verify(valid, contract)
      val expectedProblem = if (isSbt2(profile)) "Invalid literal number" else "invalid literal number"
      Assert.assertEquals(expectedProblem,
        exactAttribute(contract, "compilation-failure-inspection", "message"))

      val wrongCapitalization = updateFirst(valid, _.startsWith("##teamcity[inspection "),
        _.replace(expectedProblem, if (isSbt2(profile)) "invalid literal number" else "Invalid literal number"))
      val splitPath = updateFirst(valid, _.startsWith("##teamcity[inspection "),
        _.replace("/work/", "/other/"))
      val flowedInspectionType = updateFirst(valid, _.startsWith("##teamcity[inspectionType"),
        addFlow)
      val flowedTaskFailure = updateFirst(valid,
        line => line.contains("Compilation failed") && !line.contains("flowId="), addFlow)

      Vector(wrongCapitalization, splitPath, flowedInspectionType, flowedTaskFailure).foreach { mutated =>
        assertViolation(collect(mutated, contract), SemanticCardinalityFailure)
      }
    }
  }

  @Test def warningsKeepSeverityPathPairingAndBothDistinctLine18Diagnostics(): Unit = {
    Profiles.foreach { profile =>
      val contract = contractFor("compilation-warnings", profile)
      val valid = validTranscript("compilation-warnings", profile)
      verify(valid, contract)

      val line18 = contract.events.filter(event =>
        event.id.value.startsWith("compilation-warnings-inspection-") &&
          event.attributes.contains("line" -> exact("18"))
      )
      Assert.assertEquals(s"Line 18 inspection count for $profile", 2, line18.size)
      Assert.assertEquals(
        Set(
          "unreachable code due to variable pattern 'anything' on line 17",
          "unreachable code"
        ),
        line18.map(event => exactAttribute(event, "message")).toSet
      )

      val wrongSeverity = updateFirst(valid,
        line => line.startsWith("##teamcity[inspection ") && line.contains("line='7'"),
        _.replace("SEVERITY='WARNING'", "SEVERITY='ERROR'"))
      val splitPath = updateFirst(valid,
        line => line.startsWith("##teamcity[inspection ") && line.contains("line='17'"),
        _.replace("/work/", "/other/"))
      val firstInspection = uniqueIndex(valid,
        line => line.startsWith("##teamcity[inspection ") && line.contains("line='7'"), "first inspection")
      val firstDetail = firstInspection + 1
      val brokenPairing = valid.updated(firstInspection, valid(firstDetail)).updated(firstDetail, valid(firstInspection))
      val third = uniqueIndex(valid,
        line => line.startsWith("##teamcity[inspection ") &&
          line.contains("variable pattern") && line.contains("line='18'"), "third inspection")
      val fourth = uniqueIndex(valid,
        line => line.startsWith("##teamcity[inspection ") &&
          line.contains("message='unreachable code'") && line.contains("line='18'"), "fourth inspection")
      val duplicateLine18 = valid.updated(fourth, valid(third))

      Vector(wrongSeverity, splitPath, duplicateLine18).foreach { mutated =>
        assertViolation(collect(mutated, contract), SemanticCardinalityFailure)
      }
      assertViolation(collect(brokenPairing, contract), OrderingFailure)
    }
  }

  @Test def upToDateHasExactlyOneLifecycleAndProfileSpecificSummaryCount(): Unit = {
    Profiles.foreach { profile =>
      val contract = contractFor("compilation-up-to-date", profile)
      val valid = validTranscript("compilation-up-to-date", profile)
      verify(valid, contract)
      Assert.assertEquals(1, contract.lifecycles.size)
      Assert.assertEquals(if (isSbt2(profile)) 2 else 0, valid.count(_ == TaskSummary))

      val serviceLines = valid.filter(_.startsWith("##teamcity["))
      val duplicatedLifecycle = serviceLines ++ valid
      assertViolation(collect(duplicatedLifecycle, contract), SemanticCardinalityFailure)
      assertViolation(collect(valid :+ TaskSummary, contract), PlainOutputFailure)
      if (isSbt2(profile)) {
        assertViolation(collect(valid.dropRight(1), contract), PlainOutputFailure)
      }
    }
  }

  @Test def compoundMutationAggregatesIndependentViolationsIntoOneFinalAssertion(): Unit = {
    val profile = "sbt-1-jdk17"
    val contract = contractFor("compilation-warnings", profile).copy(processResult = Success)
    val valid = validTranscript("compilation-warnings", profile)
    val firstInspection = uniqueIndex(valid,
      line => line.startsWith("##teamcity[inspection ") && line.contains("line='7'"), "first inspection")
    val firstDetail = firstInspection + 1
    val wrongOrder = valid.updated(firstInspection, valid(firstDetail)).updated(firstDetail, valid(firstInspection))
    val wrongOwnership = updateFirst(wrongOrder,
      line => line.contains("line 17") && line.startsWith("##teamcity[message"),
      _.replace(CompilerFlow, "102:compile:compiler"))
    val wrongFinish = updateFirst(wrongOwnership, _.startsWith("##teamcity[compilationFinished"),
      _.replace("compilation-warnings", "broken-compilation"))
    val mutated = wrongFinish ++ Vector(
      "##teamcity[fixtureUnexpected value='extra']",
      "unexpected plain output"
    )
    val findings = SbtSemanticOutputVerifier.collect(mutated, contract, exitCode = 7)

    Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach(assertViolation(findings, _))
    assertBlocked(
      findings,
      OrderingFailure,
      "edge:compilation-warnings-count->compilation-warnings-finish"
    )
    Assert.assertFalse(describe(findings), findings.exists(_.category == MatcherComplexityFailure))

    val failure = expectSemanticFailure {
      SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 7)
    }
    Assert.assertEquals(findings.toSet, failure.findings.toSet)
    Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach { category =>
      Assert.assertTrue(
        s"Final assertion did not render $category:\n${failure.getMessage}",
        failure.getMessage.contains(s"[$category]")
      )
    }
    Assert.assertTrue(failure.getMessage.contains("[Blocked]"))
    Assert.assertTrue(failure.getMessage.contains(
      "edge:compilation-warnings-count->compilation-warnings-finish"
    ))
  }

  private def contractFor(scenarioId: String, profile: String): SbtSemanticContract =
    SbtOrdinaryCompilationSemanticContracts.semanticContractFor(scenarioId, profile)

  private def validTranscript(
    scenarioId: String,
    profile: String,
    includeBridge: Boolean = false
  ): Vector[String] = scenarioId match {
    case "compilation-success" | "compile-incremental" | "compilation-up-to-date" =>
      ordinaryCompilation(scenarioId, profile, includeBridge)
    case "compilation-subproject" => ordinaryCompilation(scenarioId, profile, includeBridge, Some("backend"))
    case "compilation-failure" => failureTranscript(profile)
    case "compilation-warnings" => warningsTranscript(profile, includeBridge)
    case "project-no-build-file" => noBuildFileTranscript(profile)
    case "compile-inputs" => Vector.fill(expectedSummaryCount(scenarioId, profile))(TaskSummary)
  }

  private def ordinaryCompilation(
    scenarioId: String,
    profile: String,
    includeBridge: Boolean = false,
    compilerProject: Option[String] = None
  ): Vector[String] = {
    val owner = compilerProject.getOrElse(scenarioId)
    val outputSuffix = (isSbt2(profile), compilerProject) match {
      case (false, None) => "/target/scala-2.13/classes ..."
      case (false, Some(project)) => s"/$project/target/scala-2.13/classes ..."
      case (true, None) => s"/target/out/jvm/scala-2.12.20/$scenarioId/classes ..."
      case (true, Some(project)) => s"/target/out/jvm/scala-2.12.20/$project/classes ..."
    }
    val bridge = Option.when(includeBridge)(compilerBridge).getOrElse(Vector.empty)
    Vector(
      compilationBoundary("compilationStarted", s"Scala compiler [$owner]"),
      message(CompilerFlow, "NORMAL", s"[info] compiling 1 Scala source to /work$outputSuffix")
    ) ++ bridge ++ Vector(
      message(CompilerFlow, "NORMAL", "[info] done compiling"),
      compilationBoundary("compilationFinished", s"Scala compiler [$owner]")
    ) ++ Vector.fill(expectedSummaryCount(scenarioId, profile))(TaskSummary)
  }

  private def failureTranscript(profile: String): Vector[String] = {
    val problem = if (isSbt2(profile)) "Invalid literal number" else "invalid literal number"
    val path = "/work/src/main/scala/BrokenHelloWorld.scala"
    Vector(
      compilationBoundary("compilationStarted", "Scala compiler [compilation-failure]"),
      inspectionType,
      service("inspection",
        "SEVERITY" -> "ERROR",
        "line" -> "4",
        "typeId" -> "SbtCompileProblem",
        "message" -> problem,
        "file" -> path),
      message(CompilerFlow, "ERROR",
        s"[error] $path:4: $problem\n" +
          "[error]   def main(args: Array[String]) = 123println(\"Hello, World!\")\n" +
          "[error]                                   ^"),
      message(CompilerFlow, "ERROR", "[error] one error found"),
      compilationBoundary("compilationFinished", "Scala compiler [compilation-failure]"),
      messageWithoutFlow("ERROR", "[error] (Compile / compileIncremental) Compilation failed")
    ) ++ Vector.fill(expectedSummaryCount("compilation-failure", profile))(TaskSummary)
  }

  private final case class WarningFact(line: Int, problem: String, detail: String)

  private val WarningFacts = Vector(
    WarningFact(
      7,
      "match may not be exhaustive.\nIt would fail on the following input: C",
      "match may not be exhaustive.\n" +
        "[warn] It would fail on the following input: C\n" +
        "[warn]   def printThing(t: Thing) = t match {\n" +
        "[warn]                              ^"
    ),
    WarningFact(
      17,
      "patterns after a variable pattern cannot match (SLS 8.1.1)",
      "patterns after a variable pattern cannot match (SLS 8.1.1)\n" +
        "[warn]     case anything =>\n" +
        "[warn]          ^"
    ),
    WarningFact(
      18,
      "unreachable code due to variable pattern 'anything' on line 17",
      "unreachable code due to variable pattern 'anything' on line 17\n" +
        "[warn]     case unreached =>\n" +
        "[warn]                    ^"
    ),
    WarningFact(
      18,
      "unreachable code",
      "unreachable code\n" +
        "[warn]     case unreached =>\n" +
        "[warn]                    ^"
    )
  )

  private def warningsTranscript(profile: String, includeBridge: Boolean): Vector[String] = {
    val path = "/work/AWarning.scala"
    val outputSuffix = if (isSbt2(profile))
      "/target/out/jvm/scala-2.12.20/compilation-warnings/classes ..."
    else "/target/scala-2.13/classes ..."
    val pairs = WarningFacts.flatMap { warning =>
      Vector(
        service("inspection",
          "SEVERITY" -> "WARNING",
          "line" -> warning.line.toString,
          "typeId" -> "SbtCompileProblem",
          "message" -> warning.problem,
          "file" -> path),
        message(CompilerFlow, "WARNING", s"[warn] $path:${warning.line}: ${warning.detail}")
      )
    }
    Vector(
      compilationBoundary("compilationStarted", "Scala compiler [compilation-warnings]"),
      message(CompilerFlow, "NORMAL", s"[info] compiling 1 Scala source to /work$outputSuffix")
    ) ++ Option.when(includeBridge)(compilerBridge).getOrElse(Vector.empty) ++ Vector(inspectionType) ++ pairs ++ Vector(
      message(CompilerFlow, "NORMAL", "[info] done compiling"),
      message(CompilerFlow, "WARNING", "[warn] four warnings found"),
      compilationBoundary("compilationFinished", "Scala compiler [compilation-warnings]"),
      TaskSummary
    )
  }

  private def noBuildFileTranscript(profile: String): Vector[String] =
    if (!isSbt2(profile)) Vector.empty
    else Vector(
      compilationBoundary("compilationStarted", "Scala compiler [project-no-build-file]"),
      inspectionType,
      message(CompilerFlow, "WARNING",
        "[warn] there was 1 deprecation warning; re-run with -deprecation for details"),
      message(CompilerFlow, "WARNING", "[warn] one warning found"),
      compilationBoundary("compilationFinished", "Scala compiler [project-no-build-file]")
    )

  private def compilerBridge: Vector[String] = Vector(
    message(CompilerFlow, "NORMAL",
      "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.18. Compiling..."),
    message(CompilerFlow, "NORMAL", "[info]   Compilation completed in 1.25s.")
  )

  private def inspectionType: String = service("inspectionType",
    "id" -> "SbtCompileProblem",
    "name" -> "sbt compile problem",
    "description" -> "Compile problems",
    "category" -> "Compile problems")

  private def compilationBoundary(kind: String, compiler: String): String =
    service(kind, "compiler" -> compiler, "flowId" -> CompilerFlow)

  private def message(flow: String, status: String, text: String): String =
    service("message", "status" -> status, "flowId" -> flow, "text" -> text)

  private def messageWithoutFlow(status: String, text: String): String =
    service("message", "status" -> status, "text" -> text)

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

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

  private def expectedEventCount(scenarioId: String, profile: String): Int = scenarioId match {
    case "compilation-success" | "compile-incremental" | "compilation-up-to-date" |
         "compilation-subproject" => 4
    case "compilation-failure" => 7
    case "compilation-warnings" => 14
    case "project-no-build-file" => if (isSbt2(profile)) 5 else 0
    case "compile-inputs" => 0
  }

  private def expectedLifecycleCount(scenarioId: String, profile: String): Int = scenarioId match {
    case "project-no-build-file" if !isSbt2(profile) => 0
    case "compile-inputs" => 0
    case _ => 1
  }

  private def expectedSummaryCount(scenarioId: String, profile: String): Int = scenarioId match {
    case "compilation-warnings" => 1
    case "compilation-up-to-date" if isSbt2(profile) => 2
    case "compilation-success" | "compile-incremental" | "compilation-subproject" |
         "compilation-failure" | "compile-inputs" if isSbt2(profile) => 1
    case _ => 0
  }

  private def summaryContract(count: Int): PlainOutputContract =
    if (count == 0) RejectAll
    else Patterns(Vector.fill(count)(AfterServiceMessages(SbtTaskSummary)))

  private def outputSuffix(contract: SbtSemanticContract, eventId: String): String =
    contract.events.find(_.id.value == eventId).toVector.flatMap(_.attributes).collectFirst {
      case ("text", Embedded(_, _, suffix)) => suffix
    }.getOrElse(throw new AssertionError(s"Missing embedded output suffix on $eventId."))

  private def exactAttribute(contract: SbtSemanticContract, eventId: String, name: String): String =
    exactAttribute(
      contract.events.find(_.id.value == eventId)
        .getOrElse(throw new AssertionError(s"Missing event $eventId.")),
      name
    )

  private def exactAttribute(event: ExpectedSemanticEvent, name: String): String =
    event.attributes.collectFirst { case (`name`, SemanticValuePattern.Exact(value)) => value }
      .getOrElse(throw new AssertionError(s"Missing exact attribute $name on ${event.id}."))

  private def addFlow(line: String): String =
    line.dropRight(1) + s" flowId='$CompilerFlow']"

  private def updateFirst(
    lines: Vector[String],
    predicate: String => Boolean,
    update: String => String
  ): Vector[String] = {
    val index = uniqueIndex(lines, predicate, "mutation target")
    lines.updated(index, update(lines(index)))
  }

  private def uniqueIndex(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String
  ): Int = {
    val indexes = lines.indices.filter(index => predicate(lines(index))).toVector
    require(indexes.size == 1, s"Expected one $description, found indexes $indexes.")
    indexes.head
  }

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory,
    semanticIdentity: String = ""
  ): Unit = Assert.assertTrue(
    s"Expected $category Violation${Option.when(semanticIdentity.nonEmpty)(s" for $semanticIdentity").getOrElse("")} " +
      s"in ${describe(findings)}",
    findings.exists(finding =>
      finding.category == category &&
        finding.disposition == Violation &&
        (semanticIdentity.isEmpty || finding.semanticIdentity == semanticIdentity)
    )
  )

  private def assertBlocked(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory,
    semanticIdentity: String
  ): Unit = Assert.assertTrue(
    s"Expected $category Blocked for $semanticIdentity in ${describe(findings)}",
    findings.exists(finding =>
      finding.category == category &&
        finding.disposition == Blocked &&
        finding.semanticIdentity == semanticIdentity
    )
  )

  private def expectSemanticFailure(action: => Unit): SbtSemanticVerificationException = try {
    action
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def describe(findings: Vector[SbtSemanticFailure]): String =
    findings.map(finding => (finding.category, finding.disposition, finding.semanticIdentity)).mkString(", ")

  private def isSbt2(profile: String): Boolean = profile == "sbt-2-jdk17"

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
