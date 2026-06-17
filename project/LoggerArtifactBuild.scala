/**
 * Describes one sbt plugin artifact that the integration-test harness needs.
 *
 * The main logger project keeps normal sbt cross-building enabled, but integration-test
 * preparation needs concrete task dependencies for each target runtime. Each value of this
 * type backs one helper project that reuses the logger sources with a fixed sbt/Scala pair,
 * then stages the assembled jar under a matching version directory for nested sbt runs.
 *
 * @param displayName human-readable runtime label used in sbt log messages
 * @param projectId sbt project id for the helper artifact project
 * @param sbtVersion sbt runtime version used when building the plugin artifact
 * @param scalaVersion Scala version paired with the target sbt runtime
 * @param buildTargetDirectory target subdirectory that isolates this helper project's outputs
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
