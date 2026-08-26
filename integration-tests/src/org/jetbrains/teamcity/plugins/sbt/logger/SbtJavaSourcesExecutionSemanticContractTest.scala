package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*
import org.junit.{Assert, Test}

class SbtJavaSourcesExecutionSemanticContractTest {
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import ProcessResultContract.*
  import RawSbtDebugKind.*
  import SemanticValuePattern.*
  import SbtFindingDisposition.*
  import SbtOutputVerification.*
  import SbtVerificationFailureCategory.*
  import StructuredSbtDebugKind.*

  private val Profiles = Vector("sbt-1.4-jdk8", "sbt-1-jdk8", "sbt-1-jdk17", "sbt-2-jdk17")
  private val FutureProfile = "sbt-3-jdk21"

  @Test def everySupportedCoordinateSelectsHybridAndUnknownCoordinatesRemainExact(): Unit = {
    Profiles.foreach(profile => Assert.assertTrue(
      s"Expected hybrid Java compile/run verification on $profile",
      SbtJavaSourcesExecutionSemanticContracts.CompileAndRun
        .forRuntimeProfile(profile).isInstanceOf[Hybrid]
    ))
    Assert.assertEquals(
      ExactTranscript,
      SbtJavaSourcesExecutionSemanticContracts.CompileAndRun.forRuntimeProfile(FutureProfile)
    )
  }

  @Test def everyProfileContractAcceptsItsCompleteCompilePackageAndRunShape(): Unit = Profiles.foreach { profile =>
    val mode = hybridMode(profile)
    val transcript = syntheticTranscript(profile, mode)
    verify(transcript, effectiveContract(mode), exitCode = 0)

    val contract = mode.contract
    Assert.assertEquals(if (profile == "sbt-2-jdk17") 1 else 2, contract.lifecycles.size)
    Assert.assertEquals(PlainOutputContract.DelegatedToHybrid, contract.plainOutput)
    Assert.assertTrue(contract.events.exists(_.id.value == "package-inputs"))
    Assert.assertTrue(contract.optionalGroups.exists(_.name == "java-compiler-bridge"))
  }

  @Test def stableGeneratedClassesCompilerOwnershipAndLifecycleOrderRemainStrict(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = hybridMode(profile)
    val contract = effectiveContract(mode)
    val valid = syntheticTranscript(profile, mode)
    val wrongClasses = updateUnique(valid, _.contains("Registering generated classes"), "generated classes",
      _.replace("HelloWorld.class", "Changed.class"))
    val wrongFlow = updateUnique(valid, _.contains("compiling 1 Scala source"), "compile information",
      _.replace("100:compile:compiler", "100:compile:foreign"))
    val start = uniqueIndex(valid, _.startsWith("##teamcity[compilationStarted"), "first compilation start", 1)
    val finish = uniqueIndex(valid, _.startsWith("##teamcity[compilationFinished"), "first compilation finish", 1)
    val reversed = valid.updated(start, valid(finish)).updated(finish, valid(start))

    assertViolation(collect(wrongClasses, contract, exitCode = 0), SemanticCardinalityFailure)
    assertBlocked(collect(wrongFlow, contract, exitCode = 0), FlowOwnershipFailure)
    assertViolation(collect(reversed, contract, exitCode = 0), OrderingFailure)
  }

  @Test def programOutputRuntimeAndControlledClasspathRemainStrict(): Unit = Profiles.foreach { profile =>
    val mode = hybridMode(profile)
    val contract = effectiveContract(mode)
    val valid = syntheticTranscript(profile, mode)
    val wrongGreeting = updateUnique(valid, _ == "Hello, World!", "program greeting", _ => "Hello, changed!")
    val versionPrefix = if (profile.endsWith("jdk8")) "1.8.0_" else "17.0."
    val wrongVersion = updateUnique(valid, _.startsWith(versionPrefix), "Java version", _ => "21.0.1")

    Vector(wrongGreeting, wrongVersion).foreach(lines =>
      assertViolation(collect(lines, contract, exitCode = 0), PlainOutputFailure))

    if (profile != "sbt-1.4-jdk8") {
      val wrongClasspath = updateUnique(
        valid,
        line => line.startsWith("[debug] \t/") && line.endsWith("SNAPSHOT.jar"),
        "application classpath entry",
        _.replace("java-sources-compile-run", "changed-artifact")
      )
      assertViolation(collect(wrongClasspath, contract, exitCode = 0), PlainOutputFailure)
    }
  }

  @Test def oneHybridAssertionAggregatesSemanticPlainWireProcessAndBlockedFindings(): Unit = {
    val profile = "sbt-1-jdk17"
    val mode = hybridMode(profile)
    val contract = effectiveContract(mode).copy(processResult = Success)
    val valid = syntheticTranscript(profile, mode)
    val wrongGreeting = updateUnique(valid, _ == "Hello, World!", "program greeting", _ => "Hello, changed!")
    val wrongClasses = updateUnique(wrongGreeting, _.contains("Registering generated classes"), "generated classes",
      _.replace("HelloWorld.class", "Changed.class"))
    val wrongFlow = updateUnique(
      wrongClasses,
      _.contains("compiling 1 Scala source"),
      "compile information",
      _.replace("100:compile:compiler", "100:compile:foreign"),
      ordinal = 1
    )
    val packageStart = uniqueIndex(wrongFlow, _.contains("Packaging /workspace"), "package start", 1)
    val packageDone = uniqueIndex(wrongFlow, _.contains("Done packaging"), "package completion", 1)
    val wrongOrder = wrongFlow.updated(packageStart, wrongFlow(packageDone)).updated(packageDone, wrongFlow(packageStart))
    val mutated = wrongOrder ++ Vector(
      "prefix ##teamcity[message text='malformed']",
      "##teamcity[fixtureUnexpected value='extra']"
    )
    val failure = expectFailure(SbtSemanticOutputVerifier.verify(mutated, contract, exitCode = 7))
    val findings = failure.failures

    Vector(
      WireProtocolFailure,
      SemanticCardinalityFailure,
      OrderingFailure,
      PlainOutputFailure,
      ProcessResultFailure
    ).foreach { category =>
      assertViolation(findings, category)
      Assert.assertTrue(failure.getMessage, failure.getMessage.contains(s"[$category]"))
    }
    assertBlocked(findings, FlowOwnershipFailure)
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains(s"[$FlowOwnershipFailure]"))
    Assert.assertTrue(describe(findings), findings.exists(_.disposition == Blocked))
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Violation]"))
    Assert.assertTrue(failure.getMessage, failure.getMessage.contains("[Blocked]"))
    Assert.assertFalse(describe(findings), findings.exists(_.category == MatcherComplexityFailure))
  }

  private def hybridMode(profile: String): Hybrid =
    SbtJavaSourcesExecutionSemanticContracts.CompileAndRun.forRuntimeProfile(profile) match {
      case mode: Hybrid => mode
      case other => throw new AssertionError(s"Expected Hybrid for $profile, got $other")
    }

  private def effectiveContract(mode: Hybrid): SbtSemanticContract =
    mode.contract.copy(plainOutput = mode.plainOutput)

  private def syntheticTranscript(profile: String, mode: Hybrid): Vector[String] = {
    val serviceMessages = mode.contract.events.map(event => renderEvent(event, profile))
    val plain = renderPlain(mode.plainOutput)
    if (profile == "sbt-2-jdk17") plain.take(3) ++ serviceMessages ++ plain.drop(3)
    else serviceMessages ++ plain
  }

  private def renderEvent(event: ExpectedSemanticEvent, profile: String): String = {
    def value(name: String): String = event.attributes.find(_._1 == name).map(_._2) match {
      case Some(SemanticValuePattern.Exact(expected)) => expected
      case Some(Embedded(prefix, key, suffix)) =>
        prefix + (if (key.toString.startsWith("build id:")) "100" else "/workspace") + suffix
      case Some(StructuredSbtDebug(kind)) => renderStructured(kind, profile)
      case other => throw new AssertionError(s"Cannot render $name=$other for ${event.id}.")
    }
    val attributes = event.kind match {
      case CompilationStarted | CompilationFinished =>
        Vector("compiler" -> value("compiler"), "flowId" -> "100:compile:compiler")
      case BuildLogMessage =>
        Vector("status" -> value("status"), "flowId" -> value("flowId"), "text" -> value("text"))
      case other => throw new AssertionError(s"Cannot render ${other.wireName} for ${event.id}.")
    }
    service(event.kind.wireName, attributes*)
  }

  private def renderStructured(kind: StructuredSbtDebugKind, profile: String): String = {
    val classes = classesSuffix(profile)
    kind match {
      case DependencyCheck => "[debug] not up to date. inChanged = true, force = false"
      case DependencyUpdate => "[debug] Updating ..."
      case DependencyDone => "[debug] Done updating "
      case CreatedClassFileManager => s"[debug] Created transactional ClassFileManager with tempDir = /workspace$classes.bak"
      case AboutToDeleteClassFiles => "[debug] About to delete class files:\n[debug] "
      case BackupClassFiles => "[debug] We backup class files:\n[debug] "
      case IncrementalHeader => "[debug] [zinc] IncrementalCompile -----------"
      case IncrementalCompile => "[debug] IncrementalCompile.incrementalCompile"
      case PreviousStamps => "[debug] previous = Stamps for: 0 products, 0 sources, 0 libraries"
      case CurrentSources => "[debug] current source = Set(/workspace/src/main/scala/HelloScala.scala)"
      case InitialChanges => "[debug] > initialChanges = InitialChanges(Changes(added = Set(source), removed = Set()))"
      case FullCompilation => "[debug] Full compilation, no sources in previous analysis."
      case InvalidatedSources => "[debug] all 2 sources are invalidated"
      case InitialIncludedNodes => "[debug] Initial set of included nodes: "
      case RecompileAllSources =>
        "[debug] Recompiling all sources: number of invalidated sources > 50.0 percent of all sources"
      case CompilationCycle => "[debug] compilation cycle 1"
      case CompilerBridgeRetrieval => "[debug] Returning already retrieved and compiled bridge: /cache/bridge.jar."
      case CachedCompiler => "[debug] [zinc] Running cached compiler abcdef for Scala compiler version 2.13.18"
      case CompilerArguments => "[debug] [zinc] The Scala compiler is invoked with:\n[debug] \t-classpath\n[debug] \t/workspace/classes"
      case NoChanges => "[debug] No changes"
      case ScalaCompilationTiming => "[debug] Scala compilation took 0.25 s"
      case JavaCompilerArguments => "[debug] [zinc] The Java compiler is invoked with:\n[debug] \t-classpath\n[debug] \t/workspace/classes"
      case JavacInvocation => "[debug] Attempting to call com.sun.tools.javac.api.JavacTool@abcdef directly..."
      case JavaCompilationTiming => "[debug] Java compilation took 0.25 s"
      case JavaClassfileParsing => "[debug] [zinc] classfile.Parser parsing com.jetbrains.sbt.test.HelloWorld"
      case JavaAnalysisTiming => "[debug] Java analysis took 0.25 s"
      case JavaCompilationAndAnalysisTiming => "[debug] Java compilation + analysis took 0.5 s"
      case RemoveTemporaryDirectory =>
        s"[debug] Removing the temporary directory used for backing up class files: /workspace$classes.bak"
      case WroteProducts => s"[debug] wrote /workspace$classes"
      case PackageInputMappings => inputMappings(profile)
      case SbtRunClasspath =>
        "[debug]   Classpath:\n" +
          renderClasspathEntry("java-sources-compile-run_2.13-0.1.0-SNAPSHOT.jar", jobScoped = true) + "\n" +
          renderClasspathEntry("scala-library-2.13.18.jar", jobScoped = false)
      case WrotePackage =>
        "[debug] wrote /workspace/target/java-sources-compile-run_3-0.1.0-SNAPSHOT.jar"
      case other => throw new AssertionError(s"Unexpected structured kind $other in Java execution contract.")
    }
  }

  private def inputMappings(profile: String): String = {
    val entries = if (profile == "sbt-2-jdk17") Vector(
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloScala$.class",
      "com/jetbrains/sbt/test/HelloWorld.class",
      "com/jetbrains/sbt/test/HelloScala.tasty"
    ) else Vector(
      "com",
      "com/jetbrains",
      "com/jetbrains/sbt",
      "com/jetbrains/sbt/test",
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloScala$.class",
      "com/jetbrains/sbt/test/HelloWorld.class"
    )
    val root = s"/workspace${classesSuffix(profile)}/"
    "[debug] Input file mappings:" + entries.map(entry =>
      s"\n[debug] \t$entry\n[debug] \t  $root$entry"
    ).mkString
  }

  private def renderPlain(contract: PlainOutputContract): Vector[String] = contract match {
    case PlainOutputContract.Patterns(patterns) => patterns.map(renderPlainPattern)
    case other => throw new AssertionError(s"Expected plain patterns, got $other")
  }

  private def renderPlainPattern(pattern: PlainOutputPattern): String = pattern match {
    case PlainOutputPattern.Exact(line) => line
    case JavaRuntimeVersion(8) => "1.8.0_402"
    case JavaRuntimeVersion(17) => "17.0.17"
    case JavaRuntimeHome(true) => "/opt/jdk8/jre"
    case JavaRuntimeHome(false) => "/opt/jdk17"
    case JavaRunClasspathEntry(fileName, jobScoped) => renderClasspathEntry(fileName, jobScoped)
    case SbtTaskSummary => "[success] Total time: 1.25 s, completed Aug 26, 2026, 12:00:00 PM"
    case SbtDebug(CommandExecution) => "[debug] > Exec(compile, None, None)"
    case SbtDebug(TaskEvaluation) => "[debug] Evaluating tasks: Compile / compile"
    case SbtDebug(TaskRun) => "[debug] Running task... Cancel: Signal, check cycles: false, forcegc: true"
    case BeforeServiceMessages(child) => renderPlainPattern(child)
    case other => throw new AssertionError(s"Cannot render plain pattern $other")
  }

  private def renderClasspathEntry(fileName: String, jobScoped: Boolean): String = {
    val scope = if (jobScoped) "job-1/target/abcdef/123456" else "target/abcdef/123456"
    s"[debug] \t/workspace/target/bg-jobs/sbt_abcdef/$scope/$fileName"
  }

  private def classesSuffix(profile: String): String =
    if (profile == "sbt-2-jdk17")
      "/target/out/jvm/scala-3.8.4/java-sources-compile-run/classes"
    else "/target/scala-2.13/classes"

  private def service(kind: String, attributes: (String, String)*): String =
    s"##teamcity[$kind ${attributes.map { case (name, value) =>
      s"$name='${teamCityEscape(value)}'"
    }.mkString(" ")}]"

  private def verify(lines: Vector[String], contract: SbtSemanticContract, exitCode: Int): Unit =
    SbtSemanticOutputVerifier.verify(
      lines,
      contract,
      exitCode,
      delegated = SbtDelegatedVerification(processResultVerified = true)
    )

  private def collect(
    lines: Vector[String],
    contract: SbtSemanticContract,
    exitCode: Int
  ): Vector[SbtSemanticFailure] = SbtSemanticOutputVerifier.collect(
    lines,
    contract,
    exitCode,
    delegated = SbtDelegatedVerification(processResultVerified = true)
  )

  private def updateUnique(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String,
    update: String => String,
    ordinal: Int = 1
  ): Vector[String] = {
    val index = uniqueIndex(lines, predicate, description, ordinal)
    lines.updated(index, update(lines(index)))
  }

  private def uniqueIndex(
    lines: Vector[String],
    predicate: String => Boolean,
    description: String,
    ordinal: Int
  ): Int = {
    val indexes = lines.indices.filter(index => predicate(lines(index))).toVector
    require(indexes.size >= ordinal, s"Expected $description ordinal $ordinal, found indexes $indexes.")
    indexes(ordinal - 1)
  }

  private def expectFailure(action: => Unit): SbtSemanticVerificationException = try {
    action
    throw new AssertionError("Expected semantic verification to fail.")
  } catch {
    case failure: SbtSemanticVerificationException => failure
  }

  private def assertViolation(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category Violation in ${describe(findings)}",
    findings.exists(finding => finding.category == category && finding.disposition == Violation)
  )

  private def assertBlocked(
    findings: Vector[SbtSemanticFailure],
    category: SbtVerificationFailureCategory
  ): Unit = Assert.assertTrue(
    s"Expected $category Blocked in ${describe(findings)}",
    findings.exists(finding => finding.category == category && finding.disposition == Blocked)
  )

  private def describe(findings: Vector[SbtSemanticFailure]): String =
    findings.map(finding =>
      (finding.category, finding.disposition, finding.semanticIdentity)).mkString("\n")

  private def teamCityEscape(value: String): String = value
    .replace("|", "||")
    .replace("'", "|'")
    .replace("\n", "|n")
    .replace("\r", "|r")
    .replace("[", "|[")
    .replace("]", "|]")
}
