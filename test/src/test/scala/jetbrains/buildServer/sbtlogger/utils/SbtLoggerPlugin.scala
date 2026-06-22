package jetbrains.buildServer.sbtlogger.utils

import org.jetbrains.sbt.integrationTests.SbtPluginUnderTest

/**
 * Logger-specific plugin-under-test metadata supplied to the shared sbt integration-test utilities.
 */
private[sbtlogger] object SbtLoggerPlugin {
  val UnderTest: SbtPluginUnderTest = SbtPluginUnderTest(
    jarName = "sbt-teamcity-logger.jar",
    entrypointClass = "jetbrains.buildServer.sbtlogger.SbtTeamCityLogger",
    packageCommandHint = "sbt +packageBin"
  )
}
