package jetbrains.buildServer.sbtlogger.utils

import org.jetbrains.sbt.integrationTests.SbtPluginUnderTest

/**
 * Logger-specific plugin-under-test metadata supplied to the shared sbt integration-test utilities.
 */
private[sbtlogger] object SbtLoggerPlugin {
  val UnderTest: SbtPluginUnderTest = SbtPluginUnderTest(
    entrypointClass = "jetbrains.buildServer.sbtlogger.SbtTeamCityLogger",
    packageCommandHint = "sbt prepareIntegrationTestArtifacts"
  )
}
