package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtDependencyResolutionSemanticContractTest {
  import ProcessResultContract.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private val Sbt1Profiles = Vector("sbt-1.4-jdk8", "sbt-1-jdk8", "sbt-1-jdk17")
  private val AllProfiles = Sbt1Profiles :+ "sbt-2-jdk17"
  private val DetailedProfiles = Vector("sbt-1-jdk17", "sbt-2-jdk17")
  private val FutureProfile = "sbt-3-jdk21"
  private val BuildFlow = "101:global:dependency"
  private val DependencyFlow = "teamcity-sbt-dependency-resolution"
  private val TaskSummary = "[error] Total time: 1 s"
  private val Coordinate =
    "org.example.teamcity.logger:deliberately-missing-update-dependency_2.13:0.0.0"
  private val MissingPom =
    "https://repo1.maven.org/maven2/org/example/teamcity/logger/" +
      "deliberately-missing-update-dependency_2.13/0.0.0/" +
      "deliberately-missing-update-dependency_2.13-0.0.0.pom"
  private val LibraryPom =
    "https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.pom"

  @Test def knownCoordinatesSelectTheirContractAndUnknownProfilesRemainExact(): Unit = {
    val defaultSelections = Vector(
      SbtDependencyDetailedOutcomesSemanticContracts.DefaultUpdate,
      SbtDependencyDetailedOutcomesSemanticContracts.DefaultUpdateFailure
    )
    val detailedSelections = Vector(
      SbtDependencyDetailedOutcomesSemanticContracts.Failure,
      SbtDependencyDetailedOutcomesSemanticContracts.DebugDisabled,
      SbtDependencyDetailedOutcomesSemanticContracts.PreserveConsoleDisabled
    )
    val checks = defaultSelections.flatMap(selection =>
      AllProfiles.map(profile =>
        selection.forRuntimeProfile(profile).isInstanceOf[Semantic] ->
          s"default dependency selection is not semantic for $profile") ++
        Vector((selection.forRuntimeProfile(FutureProfile) == ExactTranscript) ->
          "default dependency selection did not retain future exact fallback")) ++
      detailedSelections.flatMap(selection =>
        DetailedProfiles.map(profile =>
          selection.forRuntimeProfile(profile).isInstanceOf[Hybrid] ->
            s"detailed dependency selection is not hybrid for $profile") ++
          Vector((selection.forRuntimeProfile(FutureProfile) == ExactTranscript) ->
            "detailed dependency selection did not retain future exact fallback"))
    assertAll("dependency selection", checks)
  }

  @Test def defaultUpdateRequiresExactlyTheProfileSpecificEmptyShape(): Unit = {
    val selection = SbtDependencyDetailedOutcomesSemanticContracts.DefaultUpdate
    val checks = AllProfiles.flatMap { profile =>
      val mode = selection.forRuntimeProfile(profile)
      val valid = if (isSbt2(profile)) Vector(TaskSummary) else Vector.empty
      val unexpectedService = Vector("##teamcity[fixtureUnexpected value='extra']") ++ valid
      val unexpectedPlain = valid :+ "unexpected plain output"
      Vector(
        capturesNoFailure(valid, mode) -> s"$profile rejected its default update shape",
        hasViolation(collect(unexpectedService, mode), SemanticCardinalityFailure) ->
          s"$profile accepted a detailed service message during default update",
        hasViolation(collect(unexpectedPlain, mode), PlainOutputFailure) ->
          s"$profile accepted undeclared default-update plain output"
      )
    }
    assertAll("default dependency update", checks)
  }

  @Test def resolutionFailureKeepsUserDataExactAndDependencyFramesStructural(): Unit = {
    val selection = SbtDependencyDetailedOutcomesSemanticContracts.DefaultUpdateFailure
    val failures = Vector.newBuilder[String]
    val checks = Vector.newBuilder[(Boolean, String)]
    AllProfiles.foreach { profile =>
      val mode = selection.forRuntimeProfile(profile)
      val valid = defaultFailureTranscript(profile)
      captureFailure(failures, s"$profile default failure") { verify(valid, mode, exitCode = 1) }
      val structuralFrames = valid.map(line =>
        if (line.startsWith("##teamcity[message") && line.contains("ResolveException"))
          line.replace("CoursierDependencyResolution.scala:350", "CoursierDependencyResolution.scala:999")
            .replace("$anonfun$update$40", "update$$anonfun$99")
        else line)
      captureFailure(failures, s"$profile dependency-owned frames") {
        verify(structuralFrames, mode, exitCode = 1)
      }

      val wrongCoordinate = updateUnique(valid,
        line => line.contains("ResolveException") && !line.contains("(update)"),
        s"$profile full resolution failure",
        _.replace(Coordinate, s"$Coordinate.changed"))
      val foreignFrame = updateUnique(valid,
        line => line.contains("ResolveException") && !line.contains("(update)"),
        s"$profile full resolution failure",
        _.replace("lmcoursier.CoursierDependencyResolution", "com.foreign.DependencyResolution"))
      val differentIvyHome = updateUnique(valid,
        line => line.contains("ResolveException") && line.contains("(update)"),
        s"$profile task resolution failure",
        _.replace("/ivy/local/", "/other-ivy/local/"))
      checks += hasViolation(collect(wrongCoordinate, mode, exitCode = 1), SemanticCardinalityFailure) ->
        s"$profile accepted a changed dependency coordinate"
      checks += hasViolation(collect(foreignFrame, mode, exitCode = 1), SemanticCardinalityFailure) ->
        s"$profile accepted a foreign dependency-internal frame"
      checks += hasViolation(collect(differentIvyHome, mode, exitCode = 1), SemanticCardinalityFailure) ->
        s"$profile accepted inconsistent Ivy homes"
    }
    assertNoFailures("default dependency failure", failures.result() ++
      checks.result().collect { case (false, message) => message })
  }

  @Test def detailedFailureRequiresResourcesBlockLifecycleAndFailureMessages(): Unit = {
    val selection = SbtDependencyDetailedOutcomesSemanticContracts.Failure
    val failures = Vector.newBuilder[String]
    val checks = Vector.newBuilder[(Boolean, String)]
    DetailedProfiles.foreach { profile =>
      val mode = selection.forRuntimeProfile(profile)
      val valid = detailedFailureTranscript(profile)
      captureFailure(failures, s"$profile detailed failure") { verify(valid, mode, exitCode = 1) }
      val reversedResources = reverseThreeResources(valid)
      captureFailure(failures, s"$profile unordered detailed resources") {
        verify(reversedResources, mode, exitCode = 1)
      }
      val secondsSummary = valid.map(_.replace("finished in 23 ms", "finished in 1.13 s"))
      captureFailure(failures, s"$profile dependency summary seconds") {
        verify(secondsSummary, mode, exitCode = 1)
      }

      val missingResource = valid.filterNot(_.contains(s"$MissingPom.sha1"))
      val wrongSummary = updateUnique(valid, _.contains("2 failed download attempts"),
        s"$profile dependency summary", _.replace("2 failed download attempts", "1 failed download attempt"))
      val wrongFlow = updateUnique(valid, _.contains(s"$MissingPom.sha1"),
        s"$profile failed SHA resource", _.replace(DependencyFlow, "flow-other"))
      val close = uniqueIndex(valid, _.startsWith("##teamcity[blockClosed"), "dependency block close")
      val fullError = uniqueIndex(valid,
        line => line.contains("ResolveException") && !line.contains("(update)"), "full resolution error")
      val wrongOrder = valid.updated(close, valid(fullError)).updated(fullError, valid(close))
      checks ++= Vector(
        hasViolation(collect(missingResource, mode, exitCode = 1), SemanticCardinalityFailure) ->
          s"$profile accepted a missing failed-download resource",
        hasViolation(collect(wrongSummary, mode, exitCode = 1), SemanticCardinalityFailure) ->
          s"$profile accepted changed dependency summary counts",
        hasViolation(collect(wrongFlow, mode, exitCode = 1), FlowOwnershipFailure) ->
          s"$profile accepted a resource on a foreign flow",
        hasViolation(collect(wrongOrder, mode, exitCode = 1), OrderingFailure) ->
          s"$profile accepted the full error before block close"
      )
    }
    assertNoFailures("detailed dependency failure", failures.result() ++
      checks.result().collect { case (false, message) => message })
  }

  @Test def debugAndPreserveConsoleModesClassifyEveryPlainAndServiceLine(): Unit = {
    val checks = Vector.newBuilder[(Boolean, String)]
    val failures = Vector.newBuilder[String]
    DetailedProfiles.foreach { profile =>
      val debugMode = SbtDependencyDetailedOutcomesSemanticContracts.DebugDisabled.forRuntimeProfile(profile)
      val validDebug = debugTranscript(profile)
      captureFailure(failures, s"$profile dependency debug") { verify(validDebug, debugMode) }
      val structuralDebug = validDebug.map(_.replace("inChanged = true", "inChanged = false"))
      captureFailure(failures, s"$profile structured dependency debug") { verify(structuralDebug, debugMode) }
      val wrongDebug = updateUnique(validDebug, _.contains("Updating ..."), s"$profile update debug line",
        _.replace("Updating ...", "Resolving ..."))
      checks += hasViolation(collect(wrongDebug, debugMode), SemanticCardinalityFailure) ->
        s"$profile accepted unknown dependency debug text"

      val preserveMode = SbtDependencyDetailedOutcomesSemanticContracts.PreserveConsoleDisabled
        .forRuntimeProfile(profile)
      captureFailure(failures, s"$profile preserve-console detailed disablement") {
        verify(Vector(TaskSummary), preserveMode)
      }
      checks += hasViolation(
        collect(Vector("##teamcity[blockOpened name='Dependency resolution' flowId='foreign']", TaskSummary),
          preserveMode),
        SemanticCardinalityFailure
      ) -> s"$profile accepted detailed protocol while preserve-console was enabled"
    }
    assertNoFailures("dependency output-control modes", failures.result() ++
      checks.result().collect { case (false, message) => message })
  }

  @Test def compoundDetailedFailureReportsAllIndependentMismatchesAtOnce(): Unit = {
    val profile = "sbt-2-jdk17"
    val mode = SbtDependencyDetailedOutcomesSemanticContracts.Failure.forRuntimeProfile(profile)
    val valid = detailedFailureTranscript(profile)
    val firstResource = uniqueIndex(valid, _.contains(LibraryPom), "library resource")
    val warning = uniqueIndex(valid, _.contains("Unresolved dependencies path"), "resolution warning")
    val wrongOrder = valid.updated(firstResource, valid(warning)).updated(warning, valid(firstResource))
    val wrongOwnership = updateUnique(wrongOrder, _.startsWith("##teamcity[blockOpened"), "block open",
      _.replace(DependencyFlow, "flow-other"))
    val wrongLifecycle = updateUnique(wrongOwnership, _.startsWith("##teamcity[blockClosed"), "block close",
      _.replace("Dependency resolution", "Changed resolution"))
    val mutated = wrongLifecycle ++ Vector(
      "##teamcity[fixtureUnexpected value='extra']",
      "unexpected plain output"
    )
    val contract = effectiveContract(mode).copy(processResult = Success)
    val failure = expectFailure(SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 7))
    val categories = Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    )
    val checks = categories.flatMap(category => Vector(
      hasViolation(failure.failures, category) -> s"missing $category Violation",
      failure.getMessage.contains(s"[$category]") -> s"aggregate message omits $category"
    )) ++ Vector(
      failure.failures.exists(_.disposition == Blocked) -> "aggregate findings contain no Blocked consequence",
      failure.getMessage.contains("[Violation]") -> "aggregate message omits Violation dispositions",
      failure.getMessage.contains("[Blocked]") -> "aggregate message omits Blocked dispositions",
      !failure.failures.exists(_.category == MatcherComplexityFailure) ->
        "compound dependency fixture exceeded the matcher budget"
    )
    assertAll(s"aggregate dependency failure:\n${failure.getMessage}", checks)
  }

  @Test def dependencyPatternsRejectInvalidDeclarationsBeforeMatching(): Unit = {
    import ObservedServiceMessageKind.BuildLogMessage
    import SemanticValuePattern.*

    val ivyHome = SemanticBindingKey.path("invalid-dependency-ivy-home")
    val invalidPatterns = Vector(
      dependencyResolveFailure("not-a-coordinate", MissingPom, ivyHome, taskScoped = false),
      dependencyResolveFailure(Coordinate, "file:/not-http", ivyHome, taskScoped = false),
      dependencyResolveFailure(Coordinate, MissingPom, ivyHome, taskScoped = false, maximumInternalFrames = -1),
      dependencyResolutionSummary(-1, 0, 0, 0)
    )
    val checks = invalidPatterns.zipWithIndex.map { case (pattern, index) =>
      val findings = SbtSemanticOutputVerifier.collect(Vector.empty, SbtSemanticContract(Vector(
        ExpectedSemanticEvent(s"invalid-dependency-$index", BuildLogMessage,
          "status" -> exact(if (index == invalidPatterns.size - 1) "NORMAL" else "ERROR"),
          "text" -> pattern)
      )), exitCode = 0)
      hasViolation(findings, GoldenSyntaxFailure) -> s"invalid dependency pattern $index passed validation"
    }
    val wrongStatus = SbtSemanticOutputVerifier.collect(Vector.empty, SbtSemanticContract(Vector(
      ExpectedSemanticEvent("invalid-dependency-status", BuildLogMessage,
        "status" -> exact("NORMAL"),
        "text" -> dependencyResolveFailure(Coordinate, MissingPom, ivyHome, taskScoped = false))
    )), exitCode = 0)
    assertAll("dependency pattern declarations", checks :+
      (hasViolation(wrongStatus, GoldenSyntaxFailure) ->
        "dependency failure pattern accepted a non-ERROR event status"))
  }

  private def defaultFailureTranscript(profile: String): Vector[String] =
    resolutionWarnings("dependency-update-failure-default", profile) ++
      resolutionErrors ++ Option.when(isSbt2(profile))(TaskSummary)

  private def detailedFailureTranscript(profile: String): Vector[String] = Vector(
    service("blockOpened", "name" -> "Dependency resolution", "flowId" -> DependencyFlow),
    dependencyResource("NORMAL", s"[dependency-detailed-failure / global] local cache hit $LibraryPom"),
    dependencyResource("WARNING",
      s"[dependency-detailed-failure / global] failed download attempt $MissingPom (after 17 ms)"),
    dependencyResource("WARNING",
      s"[dependency-detailed-failure / global] failed download attempt $MissingPom.sha1 (after 19 ms)")
  ) ++ resolutionWarnings("dependency-detailed-failure", profile) ++ Vector(
    service("message", "status" -> "NORMAL", "flowId" -> DependencyFlow,
      "text" -> ("Dependency resolution finished in 23 ms: 1 local cache hit, 0 downloads, " +
        "2 failed download attempts, 0 sbt update report cache hits")),
    service("blockClosed", "name" -> "Dependency resolution", "flowId" -> DependencyFlow)
  ) ++ resolutionErrors ++ Vector(TaskSummary)

  private def resolutionWarnings(scenarioId: String, profile: String): Vector[String] =
    Vector(message("WARNING", "[warn] \n[warn] \tNote: Unresolved dependencies path:")) ++
      Option.when(isSbt2(profile))(Vector(
        message("WARNING", s"[warn] \t\t$Coordinate (/work/build.sbt#L4-4)"),
        message("WARNING", s"[warn] \t\t  +- default:${scenarioId}_2.13:0.1.0-SNAPSHOT")
      )).getOrElse(Vector.empty)

  private def resolutionErrors: Vector[String] = Vector(
    message("ERROR", resolutionDetails(taskScoped = false, includeFrames = true)),
    message("ERROR", resolutionDetails(taskScoped = true, includeFrames = false))
  )

  private def resolutionDetails(taskScoped: Boolean, includeFrames: Boolean): String = {
    val task = if (taskScoped) "(update) " else ""
    val base = Vector(
      s"[error] ${task}sbt.librarymanagement.ResolveException: Error downloading $Coordinate",
      "[error]   Not found",
      "[error]   Not found",
      s"[error]   not found: /ivy/local/org.example.teamcity.logger/" +
        "deliberately-missing-update-dependency_2.13/0.0.0/ivys/ivy.xml",
      s"[error]   not found: $MissingPom"
    )
    val frames = if (includeFrames) Vector(
      "[error] \tat lmcoursier.CoursierDependencyResolution.unresolvedWarningOrThrow" +
        "(CoursierDependencyResolution.scala:350)",
      "[error] \tat lmcoursier.CoursierDependencyResolution.$anonfun$update$40" +
        "(CoursierDependencyResolution.scala:319)",
      "[error] \tat scala.util.Either$LeftProjection.map(Either.scala:573)",
      "[error] \tat lmcoursier.CoursierDependencyResolution.update(CoursierDependencyResolution.scala:319)"
    ) else Vector.empty
    (base ++ frames).mkString("\n")
  }

  private def debugTranscript(profile: String): Vector[String] = {
    val raw = if (isSbt2(profile)) Vector(
      "[debug] > Exec(update, None, None)",
      "[debug] Evaluating tasks: update",
      "[debug] Running task... Cancel: Signal, check cycles: false, forcegc: true"
    ) else Vector.empty
    raw ++ Vector(
      message("NORMAL", "[debug] not up to date. inChanged = true, force = true"),
      message("NORMAL", "[debug] Updating ..."),
      message("NORMAL", "[debug] Done updating ")
    ) ++ Option.when(isSbt2(profile))(TaskSummary)
  }

  private def dependencyResource(status: String, text: String): String =
    service("message", "status" -> status, "flowId" -> DependencyFlow, "text" -> text)

  private def message(status: String, text: String): String =
    service("message", "status" -> status, "flowId" -> BuildFlow, "text" -> text)

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

  private def reverseThreeResources(lines: Vector[String]): Vector[String] = {
    val indexes = lines.indices.filter(index =>
      lines(index).contains("dependency-detailed-failure / global")
    ).toVector
    require(indexes.size == 3, s"Expected three detailed resources, found $indexes.")
    indexes.zip(indexes.map(lines).reverse).foldLeft(lines) { case (result, (index, line)) =>
      result.updated(index, line)
    }
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

  private def capturesNoFailure(lines: Vector[String], mode: SbtOutputVerification): Boolean = try {
    verify(lines, mode)
    true
  } catch {
    case _: Throwable => false
  }

  private def captureFailure(failures: scala.collection.mutable.Builder[String, Vector[String]], label: String)
                            (body: => Unit): Unit = try body catch {
    case failure: Throwable => failures += s"$label: ${failure.getMessage}"
  }

  private def hasViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Boolean = findings.exists(finding =>
    finding.category == category && finding.disposition == Violation)

  private def expectFailure(body: => Unit): SbtSemanticVerificationException = try {
    body
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def updateUnique(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String,
    update: String => String
  ): Vector[String] = {
    val index = uniqueIndex(lines, predicate, description)
    lines.updated(index, update(lines(index)))
  }

  private def uniqueIndex(lines: Vector[String], predicate: String => Boolean, description: String): Int = {
    val indexes = lines.indices.filter(index => predicate(lines(index))).toVector
    require(indexes.size == 1, s"Expected one $description, found indexes $indexes.")
    indexes.head
  }

  private def assertAll(context: String, checks: Seq[(Boolean, String)]): Unit =
    assertNoFailures(context, checks.collect { case (false, message) => message }.toVector)

  private def assertNoFailures(context: String, failures: Vector[String]): Unit =
    Assert.assertTrue(
      s"$context\n${failures.map(message => s" - $message").mkString("\n")}",
      failures.isEmpty
    )

  private def isSbt2(profile: String): Boolean = profile == "sbt-2-jdk17"

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
