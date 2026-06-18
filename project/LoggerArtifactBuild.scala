/**
 * Describes one private sbt plugin staging artifact that the integration-test harness needs.
 *
 * The public logger project uses Scala-driven cross publishing. Integration-test preparation
 * still needs concrete task dependencies for each target runtime, so each value of this type
 * backs a non-published helper project that reuses the logger sources with a fixed sbt/Scala
 * pair, then stages the self-contained plugin jar under a matching runtime directory for
 * nested sbt runs.
 *
 * @param displayName human-readable runtime label used in sbt log messages
 * @param projectId sbt project id for the private staging project
 * @param sbtVersion sbt runtime version used when building the plugin artifact
 * @param scalaVersion Scala version paired with the target sbt runtime
 * @param buildTargetDirectory target subdirectory that isolates this staging project's outputs
 * @param stagingDirectory directory name under target/integration-test-artifacts/tc_plugin
 */
final case class LoggerArtifactBuild(
  displayName: String,
  projectId: String,
  sbtVersion: String,
  scalaVersion: String,
  buildTargetDirectory: String,
  stagingDirectory: String
)
