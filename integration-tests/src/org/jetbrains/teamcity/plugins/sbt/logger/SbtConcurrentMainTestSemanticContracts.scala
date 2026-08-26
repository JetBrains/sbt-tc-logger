package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.utils.*

private[logger] object SbtConcurrentMainTestSemanticContracts {
  import ObservedServiceMessageKind.*
  import PlainOutputPattern.*
  import SemanticValuePattern.*
  import SbtOutputVerification.*

  val Success: SbtOutputVerificationSelection = SbtOutputVerificationSelection(
    defaultMode = Semantic(contract(OutputLayout.Sbt1, PlainOutputContract.RejectAll)),
    runtimeProfileOverrides = Map(
      "sbt-2-jdk17" -> Semantic(contract(
        OutputLayout.Sbt2,
        PlainOutputContract.Patterns(Vector(AfterServiceMessages(SbtTaskSummary)))
      ))
    )
  )

  private enum OutputLayout {
    case Sbt1, Sbt2

    def outputSuffix(project: String, configuration: String): String = this match {
      case Sbt1 => s"/$project/target/scala-2.13/$configuration ..."
      case Sbt2 => s"/target/out/jvm/scala-2.13.18/$project/$configuration ..."
    }
  }

  private final case class CompilationPart(
    events: Vector[ExpectedSemanticEvent],
    happensBefore: Set[HappensBefore],
    lifecycle: SemanticLifecycleRule,
    optionalBridge: OptionalSemanticEventGroup
  )

  private final case class ProjectPart(
    main: CompilationPart,
    test: CompilationPart,
    buildId: SemanticBindingKey
  )

  private def contract(
    layout: OutputLayout,
    plainOutput: PlainOutputContract
  ): SbtSemanticContract = {
    val outputBase = SemanticBindingKey.path("concurrent-main-test-output-base")
    val left = projectPart("left", layout, outputBase)
    val right = projectPart("right", layout, outputBase)
    val projects = Vector(left, right)
    val compilations = projects.flatMap(project => Vector(project.main, project.test))

    SbtSemanticContract(
      events = compilations.flatMap(_.events),
      happensBefore = compilations.flatMap(_.happensBefore).toSet ++ Set(
        HappensBefore(left.main.lifecycle.finish, left.test.lifecycle.start),
        HappensBefore(right.main.lifecycle.finish, right.test.lifecycle.start)
      ),
      lifecycles = compilations.map(_.lifecycle),
      optionalGroups = compilations.map(_.optionalBridge),
      distinctBindings = Set(DistinctSemanticBindings(left.buildId, right.buildId)),
      plainOutput = plainOutput
    )
  }

  private def projectPart(
    project: String,
    layout: OutputLayout,
    outputBase: SemanticBindingKey
  ): ProjectPart = {
    val buildId = SemanticBindingKey.buildId(s"$project-build")
    ProjectPart(
      compilationPart(project, "main", "compile", "classes", layout, outputBase, buildId),
      compilationPart(project, "test", "test", "test-classes", layout, outputBase, buildId),
      buildId
    )
  }

  private def compilationPart(
    project: String,
    role: String,
    flowConfiguration: String,
    outputConfiguration: String,
    layout: OutputLayout,
    outputBase: SemanticBindingKey,
    buildId: SemanticBindingKey
  ): CompilationPart = {
    val owner = s"$project-$role"
    val compiler =
      if (role == "main") s"Scala compiler [$project]"
      else s"Scala compiler in Test [$project]"
    val ownership = embedded("", buildId, s":$flowConfiguration:compiler")
    val start = ExpectedSemanticEvent(s"$owner-start", CompilationStarted,
      "compiler" -> exact(compiler))
    val info = ExpectedSemanticEvent(s"$owner-info", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> embedded(
        "[info] compiling 1 Scala source to ",
        outputBase,
        layout.outputSuffix(project, outputConfiguration)
      ))
    val done = ExpectedSemanticEvent(s"$owner-done", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> exact("[info] done compiling"))
    val finish = ExpectedSemanticEvent(s"$owner-finish", CompilationFinished,
      "compiler" -> exact(compiler))
    val bridgeAnnouncement = ExpectedSemanticEvent(s"$owner-bridge-announcement", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> compilerBridgeAnnouncement)
    val bridgeCompletion = ExpectedSemanticEvent(s"$owner-bridge-completion", BuildLogMessage,
      "status" -> exact("NORMAL"),
      "text" -> compilerBridgeCompletion)

    CompilationPart(
      events = Vector(start, info, done, finish),
      happensBefore = Set(HappensBefore(info.id, done.id)),
      lifecycle = SemanticLifecycleRule.compilation(
        owner,
        start.id.value,
        Seq(info.id.value, done.id.value),
        finish.id.value,
        ownership
      ),
      optionalBridge = OptionalSemanticEventGroup(
        s"$owner-compiler-bridge",
        Vector(bridgeAnnouncement, bridgeCompletion),
        happensBefore = Set(
          HappensBefore(info.id, bridgeAnnouncement.id),
          HappensBefore(bridgeAnnouncement.id, bridgeCompletion.id),
          HappensBefore(bridgeCompletion.id, done.id)
        ),
        ownership = Some(ownership)
      )
    )
  }
}
