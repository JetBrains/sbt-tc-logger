package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtConcurrentMainTestSemanticContractTest {
  import PlainOutputContract.*
  import PlainOutputPattern.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*

  private val RuntimeProfiles = Vector(
    "sbt-1.4-jdk8",
    "sbt-1-jdk8",
    "sbt-1-jdk17",
    "sbt-2-jdk17"
  )

  @Test def everyProfileAcceptsIndependentProjectInterleavingsWithOnlyOwnMainBeforeTest(): Unit = {
    RuntimeProfiles.foreach { profile =>
      val mode = SbtConcurrentMainTestSemanticContracts.Success.forRuntimeProfile(profile)
      Assert.assertTrue(s"Expected semantic verification for $profile", mode.isInstanceOf[Semantic])
      val contract = effectiveContract(mode)

      Assert.assertEquals(s"Compilation lifecycles for $profile", 4, contract.lifecycles.size)
      Assert.assertEquals(ProcessResultContract.DelegatedToHarness, contract.processResult)
      Assert.assertTrue(contract.happensBefore.contains(HappensBefore("left-main-finish", "left-test-start")))
      Assert.assertTrue(contract.happensBefore.contains(HappensBefore("right-main-finish", "right-test-start")))
      val declaredEdges = contract.happensBefore ++ contract.optionalGroups.flatMap(_.happensBefore)
      Assert.assertFalse(s"Cross-project edge in $profile", declaredEdges.exists { edge =>
        projectOf(edge.before) != projectOf(edge.after)
      })

      val expectedPlain =
        if (isSbt2(profile)) Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
        else RejectAll
      Assert.assertEquals(s"Plain output for $profile", expectedPlain, contract.plainOutput)

      verify(withProfilePlain(profile, projectLane(profile, "left", "101") ++ projectLane(profile, "right", "102")), mode)
      verify(withProfilePlain(profile, projectLane(profile, "right", "102") ++ projectLane(profile, "left", "101")), mode)
      verify(withProfilePlain(profile,
        compilation(profile, "left", "main", "101") ++
          compilation(profile, "right", "main", "102") ++
          compilation(profile, "right", "test", "102") ++
          compilation(profile, "left", "test", "101")
      ), mode)
    }
  }

  @Test def projectBuildIdsAreNumericSharedAcrossConfigurationsAndDistinct(): Unit = {
    val mode = modeFor("sbt-1-jdk17")
    val valid = transcript("sbt-1-jdk17")

    val changedTestBuild = valid.map(_.replace("101:test:compiler", "103:test:compiler"))
    val sharedProjectBuild = valid.map(_.replace("102:", "101:"))
    val nonNumericBuild = valid.map(_.replace("101:", "left-build:"))

    assertCategory(collect(changedTestBuild, mode), FlowOwnershipFailure)
    assertCategory(collect(sharedProjectBuild, mode), FlowOwnershipFailure)
    assertCategory(collect(nonNumericBuild, mode), SemanticCardinalityFailure)
  }

  @Test def ownMainToTestOrderIsRequiredWithoutConstrainingTheOtherProject(): Unit = {
    RuntimeProfiles.foreach { profile =>
      val mode = modeFor(profile)
      val reversedLeft = withProfilePlain(profile,
        compilation(profile, "left", "test", "101") ++
          compilation(profile, "left", "main", "101") ++
          projectLane(profile, "right", "102")
      )

      assertCategory(collect(reversedLeft, mode), OrderingFailure)
    }
  }

  @Test def compilerTextAndProfileSpecificSharedOutputBaseRemainExact(): Unit = {
    val sbt1Mode = modeFor("sbt-1-jdk17")
    val sbt1 = transcript("sbt-1-jdk17")
    val wrongCompiler = updateFirst(sbt1, _.contains("compiler='Scala compiler |[left|]'"),
      _.replace("Scala compiler |[left|]", "Scala compiler |[LEFT|]"))
    val wrongDone = updateFirst(sbt1, _.contains("done compiling"),
      _.replace("done compiling", "finished compiling"))
    val wrongSbt1Suffix = updateFirst(sbt1, _.contains("/left/target/scala-2.13/classes"),
      _.replace("scala-2.13/classes", "scala-3/classes"))
    val splitOutputBase = updateFirst(sbt1, _.contains("compiling 1 Scala source"),
      _.replace("/work/", "/other/"))

    Vector(wrongCompiler, wrongDone, wrongSbt1Suffix, splitOutputBase).foreach { mutated =>
      assertCategory(collect(mutated, sbt1Mode), SemanticCardinalityFailure)
    }

    val sbt2Mode = modeFor("sbt-2-jdk17")
    val wrongSbt2Suffix = updateFirst(transcript("sbt-2-jdk17"), _.contains("scala-2.13.18/left/classes"),
      _.replace("scala-2.13.18/left/classes", "scala-2.13.17/left/classes"))
    assertCategory(collect(wrongSbt2Suffix, sbt2Mode), SemanticCardinalityFailure)
  }

  @Test def optionalCompilerBridgePairsAreCompleteAdjacentAndInsideTheirLifecycle(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = modeFor(profile)
    val withBridges = transcript(profile, includeBridges = true)
    verify(withBridges, mode)

    val leftMainFlow = "flowId='101:compile:compiler'"
    val announcementIndex = uniqueIndex(withBridges,
      line => line.contains(leftMainFlow) && line.contains("Non-compiled module"),
      "left-main bridge announcement")
    val completionIndex = uniqueIndex(withBridges,
      line => line.contains(leftMainFlow) && line.contains("Compilation completed in"),
      "left-main bridge completion")
    val doneIndex = uniqueIndex(withBridges,
      line => line.contains(leftMainFlow) && line.contains("done compiling"),
      "left-main done message")
    require(completionIndex == announcementIndex + 1 && doneIndex == completionIndex + 1)

    val incomplete = withBridges.patch(completionIndex, Vector.empty, 1)
    val incompleteFindings = collect(incomplete, mode)
    assertCategory(incompleteFindings, SemanticCardinalityFailure)
    Assert.assertTrue(
      s"Expected compiler-bridge diagnostic in ${incompleteFindings.map(_.semanticIdentity)}",
      incompleteFindings.exists(_.semanticIdentity.contains("bridge"))
    )

    val nonAdjacent = withBridges
      .updated(completionIndex, withBridges(doneIndex))
      .updated(doneIndex, withBridges(completionIndex))
    assertViolation(
      collect(nonAdjacent, mode),
      SemanticCardinalityFailure,
      "event-assignment"
    )

    val bridgePair = withBridges.slice(announcementIndex, completionIndex + 1)
    val withoutPair = withBridges.patch(announcementIndex, Vector.empty, bridgePair.size)
    val outsideLifecycle = bridgePair ++ withoutPair
    assertViolation(
      collect(outsideLifecycle, mode),
      OrderingFailure,
      "edge:left-main-info->left-main-bridge-announcement"
    )

    assertCategory(collect(transcript(profile) :+ TaskSummary, mode), PlainOutputFailure)
    val sbt2 = transcript("sbt-2-jdk17")
    assertCategory(collect(sbt2.last +: sbt2.dropRight(1), modeFor("sbt-2-jdk17")), PlainOutputFailure)
  }

  @Test def compoundMutationAggregatesIndependentFindings(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = modeFor(profile)
    val valid = transcript(profile)
    val reordered =
      compilation(profile, "left", "test", "101") ++
        compilation(profile, "left", "main", "101") ++
        projectLane(profile, "right", "102")
    assertViolation(
      collect(reordered, mode),
      OrderingFailure,
      "edge:left-main-finish->left-test-start"
    )

    val rightMainFinish: String => Boolean = line =>
      line.contains("compilationFinished compiler='Scala compiler |[right|]'") &&
        line.contains("flowId='102:compile:compiler'")
    val ownershipOnly = updateUnique(valid, rightMainFinish, "right-main compilation finish",
      _.replace("102:compile:compiler", "101:compile:compiler"))
    assertViolation(collect(ownershipOnly, mode), FlowOwnershipFailure)

    val wrongOwnership = updateUnique(reordered, rightMainFinish, "right-main compilation finish",
      _.replace("102:compile:compiler", "101:compile:compiler"))
    val rightTestFinish: String => Boolean = line =>
      line.contains("compilationFinished compiler='Scala compiler in Test |[right|]'") &&
        line.contains("flowId='102:test:compiler'")
    val lifecycleOnly = updateUnique(valid, rightTestFinish, "right-test compilation finish",
      _.replace("Scala compiler in Test |[right|]", "Scala compiler in Test |[broken-right|]"))
    val lifecycleFindings = collect(lifecycleOnly, mode)
    assertViolation(lifecycleFindings, SemanticCardinalityFailure)
    assertViolation(lifecycleFindings, LifecycleFailure)
    Assert.assertFalse(lifecycleFindings.exists(_.category == MatcherComplexityFailure))

    val wrongLifecycle = updateUnique(
      wrongOwnership,
      rightTestFinish,
      "right-test compilation finish",
      _.replace("Scala compiler in Test |[right|]", "Scala compiler in Test |[broken-right|]")
    )
    val mutated = wrongLifecycle :+ "unexpected plain output"
    val contract = effectiveContract(mode).copy(processResult = ProcessResultContract.Success)
    val findings = SbtSemanticOutputVerifier.collect(mutated, contract, exitCode = 7)

    Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach(assertCategory(findings, _))
    Vector(
      SemanticCardinalityFailure,
      FlowOwnershipFailure,
      LifecycleFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach(assertViolation(findings, _))
    Assert.assertFalse(findings.exists(_.category == MatcherComplexityFailure))
  }

  private val TaskSummary = "[success] Total time: 1 s"

  private def modeFor(profile: String): SbtOutputVerification =
    SbtConcurrentMainTestSemanticContracts.Success.forRuntimeProfile(profile)

  private def transcript(profile: String, includeBridges: Boolean = false): Vector[String] =
    withProfilePlain(profile,
      projectLane(profile, "left", "101", includeBridges) ++
        projectLane(profile, "right", "102", includeBridges)
    )

  private def projectLane(
    profile: String,
    project: String,
    buildId: String,
    includeBridges: Boolean = false
  ): Vector[String] =
    compilation(profile, project, "main", buildId, includeBridges) ++
      compilation(profile, project, "test", buildId, includeBridges)

  private def compilation(
    profile: String,
    project: String,
    role: String,
    buildId: String,
    includeBridge: Boolean = false
  ): Vector[String] = {
    val isMain = role == "main"
    val flowConfiguration = if (isMain) "compile" else "test"
    val outputConfiguration = if (isMain) "classes" else "test-classes"
    val compiler =
      if (isMain) s"Scala compiler [$project]"
      else s"Scala compiler in Test [$project]"
    val flow = s"$buildId:$flowConfiguration:compiler"
    val suffix =
      if (isSbt2(profile)) s"/target/out/jvm/scala-2.13.18/$project/$outputConfiguration ..."
      else s"/$project/target/scala-2.13/$outputConfiguration ..."
    val bridge = Option.when(includeBridge)(Vector(
      message(flow, "[info] Non-compiled module 'compiler-bridge_2.13' for Scala 2.13.18. Compiling..."),
      message(flow, "[info]   Compilation completed in 1.25s.")
    )).getOrElse(Vector.empty)

    Vector(
      compilationBoundary("compilationStarted", compiler, flow),
      message(flow, s"[info] compiling 1 Scala source to /work$suffix")
    ) ++ bridge ++ Vector(
      message(flow, "[info] done compiling"),
      compilationBoundary("compilationFinished", compiler, flow)
    )
  }

  private def withProfilePlain(profile: String, serviceMessages: Vector[String]): Vector[String] =
    if (isSbt2(profile)) serviceMessages :+ TaskSummary else serviceMessages

  private def isSbt2(profile: String): Boolean = profile == "sbt-2-jdk17"

  private def compilationBoundary(kind: String, compiler: String, flow: String): String =
    s"##teamcity[$kind compiler='${teamCityEscape(compiler)}' flowId='$flow']"

  private def message(flow: String, value: String): String =
    s"##teamcity[message status='NORMAL' flowId='$flow' text='${teamCityEscape(value)}']"

  private def projectOf(id: SemanticEventId): String = id.value.takeWhile(_ != '-')

  private def effectiveContract(mode: SbtOutputVerification): SbtSemanticContract = mode match {
    case Semantic(contract) => contract
    case other => throw new AssertionError(s"Concurrent main/test scenario must use semantic verification, got $other")
  }

  private def verify(lines: Vector[String], mode: SbtOutputVerification): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      effectiveContract(mode),
      exitCode = 0,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(lines: Vector[String], mode: SbtOutputVerification): Vector[SbtSemanticFailure] =
    SbtSemanticOutputVerifier.collect(
      lines,
      effectiveContract(mode),
      exitCode = 0,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def updateFirst(
    lines: Vector[String],
    predicate: String => Boolean,
    update: String => String
  ): Vector[String] = {
    val index = lines.indexWhere(predicate)
    require(index >= 0, "Synthetic transcript mutation target was not found.")
    lines.updated(index, update(lines(index)))
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

  private def uniqueIndex(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String
  ): Int = {
    val indexes = lines.indices.filter(index => predicate(lines(index))).toVector
    require(indexes.size == 1, s"Expected one $description, found indexes $indexes.")
    indexes.head
  }

  private def assertCategory(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category in ${findings.map(finding => finding.category -> finding.semanticIdentity)}",
    findings.exists(_.category == category)
  )

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory,
    semanticIdentity: String = ""
  ): Unit = Assert.assertTrue(
    s"Expected $category Violation${Option.when(semanticIdentity.nonEmpty)(s" for $semanticIdentity").getOrElse("")} " +
      s"in ${findings.map(finding => (finding.category, finding.disposition, finding.semanticIdentity))}",
    findings.exists(finding =>
      finding.category == category &&
        finding.disposition == Violation &&
        (semanticIdentity.isEmpty || finding.semanticIdentity == semanticIdentity)
    )
  )

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
