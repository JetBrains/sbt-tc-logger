package org.jetbrains.teamcity.plugins.sbt.logger.utils

/**
 * One fixture-backed, exact-transcript integration scenario.
 *
 * Setup commands run before the logger-status boundary and may prepare the fixture without becoming part of the
 * product-output contract. Behaviour commands run after the boundary and their complete output is compared with the
 * profile-specific golden beside the fixture.
 */
final case class SbtLoggerOutputTestCase(
  scenarioId: String,
  fixture: String,
  fixtureRootRelativePath: Option[String] = None,
  setupCommands: Seq[String],
  behaviorCommands: Seq[String],
  expectedResult: SbtProcessResultExpectation,
  sbtOptions: Seq[String] = Seq("--error"),
  teamCityEnvironment: Boolean = true
) {
  require(
    scenarioId.matches("[a-z0-9]+(?:-[a-z0-9]+)*"),
    s"Invalid scenarioId '$scenarioId'; use stable lowercase kebab-case."
  )
  require(behaviorCommands.nonEmpty, s"Scenario '$scenarioId' must have at least one behavior command.")
}

/** The nested process result remains independent from the transcript contract. */
enum SbtProcessResultExpectation {
  case Success, Failure
}
