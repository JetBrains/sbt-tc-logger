package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtCompilationOutputSemanticContractTest {
  import ObservedServiceMessageKind.*
  import PlainOutputContract.*
  import ProcessResultContract.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*
  import StructuredSbtDebugKind.*

  private val Profiles = Vector(
    "sbt-1.4-jdk8",
    "sbt-1-jdk8",
    "sbt-1-jdk17",
    "sbt-2-jdk17"
  )
  private val TaskSummary = "[success] Total time: 1 s"

  @Test def knownProfilesUseDeclaredModesAndFutureProfilesRemainExact(): Unit = {
    Profiles.foreach { profile =>
      Assert.assertTrue(
        s"Test compilation must be semantic on $profile",
        SbtCompilationOutputSemanticContracts.TestCompilationFailure
          .forRuntimeProfile(profile).isInstanceOf[Semantic]
      )
      Assert.assertTrue(
        s"Compiler error-level control must be hybrid on $profile",
        SbtCompilationOutputSemanticContracts.CompilerLogLevelError
          .forRuntimeProfile(profile).isInstanceOf[Hybrid]
      )
      Assert.assertTrue(
        s"Compiler debug-level control must be hybrid on $profile",
        SbtCompilationOutputSemanticContracts.CompilerLogLevelDebug
          .forRuntimeProfile(profile).isInstanceOf[Hybrid]
      )
      val preserve = SbtCompilationOutputSemanticContracts.PreserveConsole.forRuntimeProfile(profile)
      Assert.assertEquals(
        s"Preserve-console exact canary on $profile",
        profile == "sbt-1.4-jdk8",
        preserve == ExactTranscript
      )
      if (profile != "sbt-1.4-jdk8") Assert.assertTrue(preserve.isInstanceOf[Hybrid])
    }

    val future = "sbt-3-jdk25"
    Vector(
      SbtCompilationOutputSemanticContracts.TestCompilationFailure,
      SbtCompilationOutputSemanticContracts.PreserveConsole,
      SbtCompilationOutputSemanticContracts.CompilerLogLevelError,
      SbtCompilationOutputSemanticContracts.CompilerLogLevelDebug
    ).foreach(selection => Assert.assertEquals(ExactTranscript, selection.forRuntimeProfile(future)))
  }

  @Test def everyProfileAcceptsItsValidCompilationOutputShapes(): Unit = {
    Profiles.foreach { profile =>
      verifyMode(testCompilationFailure(profile),
        SbtCompilationOutputSemanticContracts.TestCompilationFailure.forRuntimeProfile(profile))
      verifyMode(Vector.empty,
        SbtCompilationOutputSemanticContracts.CompilerLogLevelError.forRuntimeProfile(profile))
      verifyMode(compilerDebug(profile),
        SbtCompilationOutputSemanticContracts.CompilerLogLevelDebug.forRuntimeProfile(profile))

      val preserveContract = SbtCompilationOutputSemanticContracts.preserveConsoleContract(profile)
      val preservePlain = SbtCompilationOutputSemanticContracts.preserveConsolePlainOutput(profile)
      verify(preserveConsole(profile), preserveContract.copy(plainOutput = preservePlain))
      verify(preserveConsole(profile, includeBridge = true), preserveContract.copy(plainOutput = preservePlain))
    }
  }

  @Test def testCompilationRequiresMainBeforeTestWithSharedBuildAndDistinctCompilerRoles(): Unit = {
    Profiles.foreach { profile =>
      val contract = SbtCompilationOutputSemanticContracts.testCompilationFailureContract(profile)
      val valid = testCompilationFailure(profile)
      verify(valid, contract)
      Assert.assertEquals(12, contract.events.size)
      Assert.assertEquals(2, contract.lifecycles.size)
      Assert.assertEquals(2, contract.optionalGroups.size)

      val testStart = uniqueIndex(valid,
        line => line.startsWith("##teamcity[compilationStarted") && line.contains("in Test"),
        "test compilation start")
      val mainFinish = uniqueIndex(valid,
        line => line.startsWith("##teamcity[compilationFinished") && !line.contains("in Test"),
        "main compilation finish")
      val reordered = valid.updated(testStart, valid(mainFinish)).updated(mainFinish, valid(testStart))
      assertViolation(collect(reordered, contract), OrderingFailure)

      val wrongTestOwner = updateFirst(valid,
        line => line.contains("one error found") && line.contains("flowId="),
        _.replace("101:test:compiler", "102:test:compiler"))
      assertViolation(collect(wrongTestOwner, contract), FlowOwnershipFailure)

      val wrongCompilerRole = updateFirst(valid,
        line => line.startsWith("##teamcity[compilationFinished") && line.contains("in Test"),
        _.replace("Scala compiler in Test", "Scala compiler"))
      assertViolation(collect(wrongCompilerRole, contract), SemanticCardinalityFailure)

      val wrongSourceBase = updateFirst(valid, _.startsWith("##teamcity[inspection "),
        _.replace("/work/src/test", "/other/src/test"))
      assertViolation(collect(wrongSourceBase, contract), SemanticCardinalityFailure)

      val extraSummary = valid :+ TaskSummary
      assertViolation(collect(extraSummary, contract), PlainOutputFailure)
    }
  }

  @Test def testCompilationCompilerBridgesAreCompleteOwnedAndInsideTheirLifecycles(): Unit = {
    Profiles.foreach { profile =>
      val contract = SbtCompilationOutputSemanticContracts.testCompilationFailureContract(profile)
      val cold = testCompilationFailure(profile, includeBridges = true)
      verify(cold, contract)
      val announcements = cold.indices.filter(index => cold(index).contains("Non-compiled module")).toVector
      val completions = cold.indices.filter(index => cold(index).contains("Compilation completed in")).toVector
      Assert.assertEquals(2, announcements.size)
      Assert.assertEquals(2, completions.size)

      assertViolation(collect(cold.patch(completions.last, Vector.empty, 1), contract),
        SemanticCardinalityFailure)
      val wrongOwner = cold.updated(announcements.last,
        cold(announcements.last).replace("101:test:compiler", "102:test:compiler"))
      assertViolation(collect(wrongOwner, contract), FlowOwnershipFailure)
    }
  }

  @Test def preserveConsoleKeepsEveryRawCompilerLineAndParsedInspection(): Unit = {
    Profiles.foreach { profile =>
      val contract = SbtCompilationOutputSemanticContracts.preserveConsoleContract(profile).copy(
        plainOutput = SbtCompilationOutputSemanticContracts.preserveConsolePlainOutput(profile)
      )
      val valid = preserveConsole(profile)
      verify(valid, contract)

      val wrongTarget = updateFirst(valid, _.startsWith("[info] compiling"),
        _.replace("/classes ...", "/different ..."))
      val wrongDiagnosticPath = updateFirst(valid, _.startsWith("[error] /work/"),
        _.replace("/work/", "/other/"))
      val wrongCaret = updateFirst(valid, _.startsWith("[error]                                   ^"),
        _ + "^")
      val missingSummary = valid.dropRight(1)
      Vector(wrongTarget, wrongDiagnosticPath, wrongCaret, missingSummary).foreach { mutated =>
        assertViolation(collect(mutated, contract), PlainOutputFailure)
      }

      val extraServiceMessage = valid :+ messageWithoutFlow("NORMAL", "unexpected")
      assertViolation(collect(extraServiceMessage, contract), SemanticCardinalityFailure)
    }
  }

  @Test def compilerLogLevelsRequireExactAbsenceOrFiniteStructuredDebugEvents(): Unit = {
    Profiles.foreach { profile =>
      val errorMode = SbtCompilationOutputSemanticContracts.CompilerLogLevelError.forRuntimeProfile(profile)
      verifyMode(Vector.empty, errorMode)
      assertViolation(collectMode(Vector(message("101:compile:compiler", "NORMAL", "unexpected")), errorMode),
        SemanticCardinalityFailure)
      assertViolation(collectMode(Vector("unexpected plain output"), errorMode), PlainOutputFailure)

      val debugContract = SbtCompilationOutputSemanticContracts.compilerLogLevelDebugContract(profile)
      val valid = compilerDebug(profile)
      verify(valid, debugContract.copy(plainOutput = RejectAll))
      val expectedCount = profile match {
        case "sbt-1.4-jdk8" => 13
        case "sbt-2-jdk17" => 17
        case _ => 16
      }
      Assert.assertEquals(expectedCount, debugContract.events.size)

      val wrongDependencyOwner = updateFirst(valid, _.contains("not up to date"),
        _.replace("101:global:dependency", "102:global:dependency"))
      assertViolation(collect(wrongDependencyOwner, debugContract.copy(plainOutput = RejectAll)),
        FlowOwnershipFailure)
      val wrongDebugText = updateFirst(valid, _.contains("IncrementalCompile.incrementalCompile"),
        _.replace("incrementalCompile", "changed"))
      assertViolation(collect(wrongDebugText, debugContract.copy(plainOutput = RejectAll)),
        SemanticCardinalityFailure)

      val independentFlowInterleaving = interleaveCompilerDebugFlows(valid)
      verify(independentFlowInterleaving, debugContract.copy(plainOutput = RejectAll))
    }
  }

  @Test def oneThrownFailureContainsIndependentSemanticPlainAndProcessFindings(): Unit = {
    val profile = "sbt-1-jdk17"
    val base = SbtCompilationOutputSemanticContracts.preserveConsoleContract(profile)
    val contract = base.copy(
      plainOutput = SbtCompilationOutputSemanticContracts.preserveConsolePlainOutput(profile),
      processResult = Success
    )
    val valid = preserveConsole(profile)
    val wrongInspection = updateFirst(valid, _.startsWith("##teamcity[inspection "),
      _.replace("invalid literal number", "different problem"))
    val mutated = wrongInspection.updated(0, "unexpected plain output") :+
      messageWithoutFlow("NORMAL", "extra service message")
    val failure = expectFailure(SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 9))

    Vector(SemanticCardinalityFailure, PlainOutputFailure, ProcessResultFailure).foreach { category =>
      Assert.assertTrue(failure.getMessage, failure.findings.exists(_.category == category))
      Assert.assertTrue(failure.getMessage.contains(category.toString))
    }
  }

  private def testCompilationFailure(
    profile: String,
    includeBridges: Boolean = false
  ): Vector[String] = {
    val scenarioId = "test-compilation-failure"
    val mainFlow = "101:compile:compiler"
    val testFlow = "101:test:compiler"
    val mainTarget = if (isSbt2(profile))
      s"/target/out/jvm/scala-2.12.20/$scenarioId/classes"
    else "/target/scala-2.13/classes"
    val testTarget = if (isSbt2(profile))
      s"/target/out/jvm/scala-2.12.20/$scenarioId/test-classes"
    else "/target/scala-2.13/test-classes"
    val mainBridge = Option.when(includeBridges)(compilerBridge(mainFlow)).getOrElse(Vector.empty)
    val testBridge = Option.when(includeBridges)(compilerBridge(testFlow)).getOrElse(Vector.empty)
    val path = "/work/src/test/scala/BrokenTest.scala"

    Vector(
      compilationBoundary("compilationStarted", s"Scala compiler [$scenarioId]", mainFlow),
      message(mainFlow, "NORMAL", s"[info] compiling 1 Scala source to /work$mainTarget ...")
    ) ++ mainBridge ++ Vector(
      message(mainFlow, "NORMAL", "[info] done compiling"),
      compilationBoundary("compilationFinished", s"Scala compiler [$scenarioId]", mainFlow),
      compilationBoundary("compilationStarted", s"Scala compiler in Test [$scenarioId]", testFlow),
      message(testFlow, "NORMAL", s"[info] compiling 1 Scala source to /work$testTarget ...")
    ) ++ testBridge ++ Vector(
      inspectionType,
      service("inspection",
        "SEVERITY" -> "ERROR",
        "line" -> "2",
        "typeId" -> "SbtCompileProblem",
        "message" -> "type mismatch;\n found   : Int(1)\n required: String",
        "file" -> path),
      message(testFlow, "ERROR",
        s"[error] $path:2: type mismatch;\n" +
          "[error]  found   : Int(1)\n" +
          "[error]  required: String\n" +
          "[error]   val value: String = 1\n" +
          "[error]                       ^"),
      message(testFlow, "ERROR", "[error] one error found"),
      compilationBoundary("compilationFinished", s"Scala compiler in Test [$scenarioId]", testFlow),
      messageWithoutFlow("ERROR", "[error] (Test / compileIncremental) Compilation failed")
    ) ++ Option.when(isSbt2(profile))(TaskSummary)
  }

  private def preserveConsole(
    profile: String,
    includeBridge: Boolean = false
  ): Vector[String] = {
    val scenarioId = "compilation-preserve-console"
    val target = if (isSbt2(profile))
      s"/target/out/jvm/scala-2.12.20/$scenarioId/classes"
    else "/target/scala-2.13/classes"
    val problem = if (isSbt2(profile)) "Invalid literal number" else "invalid literal number"
    val path = "/work/src/main/scala/BrokenHelloWorld.scala"
    Vector(s"[info] compiling 1 Scala source to /work$target ...") ++
      Option.when(includeBridge)(rawCompilerBridge).getOrElse(Vector.empty) ++ Vector(
        inspectionType,
        service("inspection",
          "SEVERITY" -> "ERROR",
          "line" -> "2",
          "typeId" -> "SbtCompileProblem",
          "message" -> problem,
          "file" -> path),
        s"[error] $path:2:35: $problem",
        "[error]   def main(args: Array[String]) = 123println(\"Hello, World!\")",
        "[error]                                   ^",
        "[error] one error found",
        "[error] (Compile / compileIncremental) Compilation failed",
        TaskSummary
      )
  }

  private def compilerDebug(profile: String): Vector[String] = {
    val buildId = "101"
    val dependency = Vector(DependencyCheck, DependencyUpdate, DependencyDone).map { kind =>
      message(s"$buildId:global:dependency", "NORMAL", debugText(kind))
    }
    val incKinds = profile match {
      case "sbt-1.4-jdk8" => Vector(CreatedClassFileManager, RemoveTemporaryDirectory)
      case _ => Vector(
        CreatedClassFileManager,
        AboutToDeleteClassFiles,
        BackupClassFiles,
        CreatedClassFileManager,
        RemoveTemporaryDirectory
      )
    }
    val compilerKinds = Vector(
      IncrementalHeader,
      IncrementalCompile,
      PreviousStamps,
      CurrentSources,
      InitialChanges,
      FullCompilation
    ) ++ Option.when(isSbt2(profile))(WroteProducts)
    val incOptions = incKinds.map { kind =>
      message(s"$buildId:compile:general:incOptions", "NORMAL", debugText(kind))
    }
    val compiler = compilerKinds.map { kind =>
      message(s"$buildId:compile:compiler", "NORMAL", debugText(kind))
    }
    dependency ++ incOptions ++ Vector(
      compilationBoundary("compilationStarted", "Scala compiler [compiler-log-level-debug]",
        s"$buildId:compile:compiler")
    ) ++ compiler ++ Vector(
      compilationBoundary("compilationFinished", "Scala compiler [compiler-log-level-debug]",
        s"$buildId:compile:compiler")
    )
  }

  private def interleaveCompilerDebugFlows(lines: Vector[String]): Vector[String] = {
    val dependency = lines.filter(_.contains(":global:dependency"))
    val incOptions = lines.filter(_.contains(":compile:general:incOptions"))
    val compiler = lines.filter(line =>
      line.contains(":compile:compiler") || line.startsWith("##teamcity[compilation"))
    compiler.take(1) ++ dependency ++ compiler.slice(1, 3) ++ incOptions ++ compiler.drop(3)
  }

  private def debugText(kind: StructuredSbtDebugKind): String = kind match {
    case DependencyCheck => "[debug] not up to date. inChanged = true, force = false"
    case DependencyUpdate => "[debug] Updating ..."
    case DependencyDone => "[debug] Done updating "
    case IncrementalHeader => "[debug] [zinc] IncrementalCompile -----------"
    case IncrementalCompile => "[debug] IncrementalCompile.incrementalCompile"
    case PreviousStamps => "[debug] previous = Stamps for: 0 products, 0 sources, 0 libraries"
    case CurrentSources => "[debug] current source = Set()"
    case InitialChanges => "[debug] > initialChanges = InitialChanges(...)"
    case FullCompilation => "[debug] Full compilation, no sources in previous analysis."
    case CreatedClassFileManager =>
      "[debug] Created transactional ClassFileManager with tempDir = /work/target/classes.bak"
    case AboutToDeleteClassFiles => "[debug] About to delete class files:\n[debug] "
    case BackupClassFiles => "[debug] We backup class files:\n[debug] "
    case RemoveTemporaryDirectory =>
      "[debug] Removing the temporary directory used for backing up class files: /work/target/classes.bak"
    case WroteProducts => "[debug] wrote /work/target/classes"
    case other => throw new AssertionError(s"No compiler-output representative for $other")
  }

  private def verifyMode(lines: Vector[String], mode: SbtOutputVerification): Unit = mode match {
    case Semantic(contract) => verify(lines, contract)
    case Hybrid(contract, plainOutput) => verify(lines, contract.copy(plainOutput = plainOutput))
    case ExactTranscript => throw new AssertionError("Synthetic verification requires a semantic or hybrid mode.")
  }

  private def collectMode(
    lines: Vector[String],
    mode: SbtOutputVerification
  ): Vector[SbtSemanticFailure] = mode match {
    case Semantic(contract) => collect(lines, contract)
    case Hybrid(contract, plainOutput) => collect(lines, contract.copy(plainOutput = plainOutput))
    case ExactTranscript => throw new AssertionError("Synthetic collection requires a semantic or hybrid mode.")
  }

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

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    describe(findings),
    findings.exists(finding => finding.category == category && finding.disposition == Violation)
  )

  private def expectFailure(body: => Unit): SbtSemanticVerificationException = try {
    body
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

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

  private def compilationBoundary(kind: String, compiler: String, flow: String): String =
    service(kind, "compiler" -> compiler, "flowId" -> flow)

  private def inspectionType: String = service("inspectionType",
    "id" -> "SbtCompileProblem",
    "name" -> "sbt compile problem",
    "description" -> "Compile problems",
    "category" -> "Compile problems")

  private def compilerBridge(flow: String): Vector[String] = Vector(
    message(flow, "NORMAL",
      "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.18. Compiling..."),
    message(flow, "NORMAL", "[info]   Compilation completed in 1.25s.")
  )

  private def rawCompilerBridge: Vector[String] = Vector(
    "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.18. Compiling...",
    "[info]   Compilation completed in 1.25s."
  )

  private def message(flow: String, status: String, text: String): String =
    service("message", "status" -> status, "flowId" -> flow, "text" -> text)

  private def messageWithoutFlow(status: String, text: String): String =
    service("message", "status" -> status, "text" -> text)

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

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
