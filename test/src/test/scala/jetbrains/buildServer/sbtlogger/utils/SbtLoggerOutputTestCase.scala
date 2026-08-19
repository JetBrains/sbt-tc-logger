package jetbrains.buildServer.sbtlogger.utils

import org.junit.Assert.assertTrue

/**
 * Describes one fixture-backed logger-output scenario.
 *
 * @param fixture            fixture directory under the selected runtime test-data root.
 * @param fixtureRootRelativePath optional fixture-root override, relative to the repository root. This is for scenarios
 *                                whose support starts later than the runtime's baseline fixture corpus.
 * @param sbtCommands        sbt commands sent through the nested process command transport.
 * @param sbtOptions         launcher command-line options passed before the command transport.
 * @param expectations       named expected-output groups and their flow-ownership scopes; defaults to `output.txt`.
 * @param verifyOutput       whether fixture regex expectations are checked; lifecycle-only cases may have no stable SBT output.
 * @param expectedExitCode   expected result of the nested sbt process.
 * @param failurePropagation expected evidence that a deliberately failed task propagated its failure through sbt.
 * @param compilationLifecycle optional strict lifecycle contract for compilation TeamCity messages.
 * @param dependencyLifecycle optional strict lifecycle contract for dependency-resolution TeamCity blocks.
 * @param teamCityEnvironment whether the nested process receives `TEAMCITY_VERSION`; when false, an inherited value is
 *                            removed before the process starts.
 * @param expectNoTeamCityMessages asserts the logger remains completely inactive when TeamCity is absent.
 * @param isolateSbtServer runs the nested process with a fresh SBT global base and server directory. This is useful
 *                         for tests that load the logger with mutually exclusive JVM properties: `apply` is deliberately
 *                         idempotent within a long-lived SBT server.
 */
final case class SbtLoggerOutputTestCase(
  fixture: String,
  fixtureRootRelativePath: Option[String] = None,
  sbtCommands: Seq[String],
  sbtOptions: Seq[String] = Seq("--error"),
  expectations: ExpectationSet = ExpectationSet.default,
  verifyOutput: Boolean = true,
  expectedExitCode: SbtExitCodeExpectation = SbtExitCodeExpectation.Any,
  failurePropagation: SbtFailurePropagationExpectation = SbtFailurePropagationExpectation.NotRequired,
  compilationLifecycle: Option[SbtCompilationLifecycleExpectation] = None,
  dependencyLifecycle: Option[SbtDependencyLifecycleExpectation] = None,
  teamCityEnvironment: Boolean = true,
  expectNoTeamCityMessages: Boolean = false,
  isolateSbtServer: Boolean = false
)

/** The complete expected-output contract selected by one [[SbtLoggerOutputTestCase]]. */
final case class ExpectationSet(scopes: Seq[FlowScope])

/** A collection of groups allowed to match the same concrete TeamCity flow IDs. */
final case class FlowScope(name: String, groups: Seq[AssertionGroup])

/** One ordered regex subsequence read from an existing expected-output fixture file. */
final case class AssertionGroup(
  name: String,
  fileName: String,
  minimumOccurrences: Int = 1
)

object ExpectationSet {
  val default: ExpectationSet = singleFile("output.txt")

  def singleFile(fileName: String): ExpectationSet =
    oneScope(groupName(fileName), AssertionGroup(groupName(fileName), fileName))

  def oneScope(name: String, groups: AssertionGroup*): ExpectationSet =
    ExpectationSet(Seq(FlowScope(name, groups)))

  private def groupName(fileName: String): String =
    fileName.stripSuffix(".txt").replaceAll("[^A-Za-z0-9]+", "-")
}

/** Named multi-group fixture mappings used by the runtime integration suites. */
private[sbtlogger] object SbtOutputExpectations {
  val multiProjectCompilation: ExpectationSet =
    ExpectationSet.oneScope(
      "multi-project-compilation",
      AssertionGroup("multi-project-events", "output.txt")
    )

  val scalaTestPassAndFailure: ExpectationSet =
    ExpectationSet.oneScope(
      "scala-test-run",
      AssertionGroup("example-spec", "output.txt"),
      AssertionGroup("list-flat-spec", "output1.txt")
    )

  val scalaTestParallelEvents: ExpectationSet = ExpectationSet(Seq(
    FlowScope("direct-non-parallel", Seq(
      AssertionGroup("direct-non-parallel-passing", "output.txt"),
      AssertionGroup("direct-non-parallel-failing", "output1.txt")
    )),
    FlowScope("direct-parallel", Seq(
      AssertionGroup("direct-parallel-passing", "output2.txt"),
      AssertionGroup("direct-parallel-failing", "output4.txt")
    )),
    FlowScope("non-parallel-suite", Seq(
      AssertionGroup("suite-parallel-failing", "output5.txt"),
      AssertionGroup("suite-non-parallel-failing", "output8.txt"),
      AssertionGroup("suite-non-parallel-passing", "output9.txt"),
      AssertionGroup("suite-parallel-passing", "output11.txt")
    ))
  ))
}

/** Exit-code contract for a fixture-backed nested sbt invocation. */
sealed trait SbtExitCodeExpectation

object SbtExitCodeExpectation {
  /** The scenario's output is relevant but its exit status is not. */
  case object Any extends SbtExitCodeExpectation
  /** The command must complete successfully. */
  case object Zero extends SbtExitCodeExpectation
  /** The command must fail, proving a captured compilation failure was rethrown. */
  case object NonZero extends SbtExitCodeExpectation
}

/** Test-only evidence required to prove that a failed task was propagated by the nested sbt runtime. */
sealed trait SbtFailurePropagationExpectation

object SbtFailurePropagationExpectation {
  /** No failure-propagation proof is needed for this scenario. */
  case object NotRequired extends SbtFailurePropagationExpectation
  /** Modern sbt runtimes expose propagated task failures through their process status. */
  case object ProcessExitNonZero extends SbtFailurePropagationExpectation
  def setupCommand(expectation: SbtFailurePropagationExpectation): Option[String] = None

  def assertObserved(
    expectation: SbtFailurePropagationExpectation,
    exitCode: Int,
    processOutput: String
  ): Unit = expectation match {
    case NotRequired =>
    case ProcessExitNonZero =>
      assertTrue(s"Expected nested sbt command to fail, but it exited with $exitCode", exitCode != 0)
  }
}

/** Strict lifecycle requirements for a focused compilation-regression fixture. */
sealed trait SbtCompilationLifecycleExpectation

object SbtCompilationLifecycleExpectation {
  /** Requires that a preparation-only task did not invent a compiler lifecycle. */
  case object Absent extends SbtCompilationLifecycleExpectation

  /** Requires exactly this many non-overlapping compiler lifecycle pairs; a flow may be reused sequentially. */
  final case class Complete(expectedClosures: Int) extends SbtCompilationLifecycleExpectation {
    require(expectedClosures > 0, "A lifecycle regression fixture must require at least one closure.")
  }

}

/** Strict lifecycle requirements for a focused dependency-resolution regression fixture. */
sealed trait SbtDependencyLifecycleExpectation

object SbtDependencyLifecycleExpectation {
  /** The opt-in detailed dependency reporter must stay entirely silent by default. */
  case object Absent extends SbtDependencyLifecycleExpectation

  /** Requires every observed dependency block to have one opener, one later closer, and no compiler-flow reuse. */
  final case class Complete(expectedClosures: Int) extends SbtDependencyLifecycleExpectation {
    require(expectedClosures > 0, "A dependency-lifecycle regression fixture must require at least one closure.")
  }
}
