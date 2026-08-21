package org.jetbrains.teamcity.plugins.sbt.logger.utils

import org.jetbrains.sbt.integrationTests.SbtPluginUnderTest

/**
 * Logger-specific plugin-under-test metadata supplied to the shared sbt integration-test utilities.
 */
private[logger] object SbtLoggerPlugin {
  val UnderTest: SbtPluginUnderTest = SbtPluginUnderTest(
    entrypointClass = "org.jetbrains.teamcity.plugins.sbt.logger.SbtTeamCityLogger",
    packageCommandHint = "sbt prepareIntegrationTestArtifacts"
  )
}
