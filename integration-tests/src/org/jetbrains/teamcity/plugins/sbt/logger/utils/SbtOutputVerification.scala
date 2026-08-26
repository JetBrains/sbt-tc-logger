package org.jetbrains.teamcity.plugins.sbt.logger.utils

/** Selects the contract layer which owns one bounded integration-test transcript. */
private[logger] sealed trait SbtOutputVerification

private[logger] object SbtOutputVerification {
  /** Preserves the historical whole-transcript comparison. */
  case object ExactTranscript extends SbtOutputVerification

  /** Verifies every service message semantically and rejects or declaratively classifies every plain line. */
  final case class Semantic(contract: SbtSemanticContract) extends SbtOutputVerification {
    require(
      contract.plainOutput != PlainOutputContract.DelegatedToHybrid,
      "Semantic verification cannot delegate plain output to Hybrid mode."
    )
    require(
      contract.processResult == ProcessResultContract.DelegatedToHarness,
      "The outer integration-test scenario owns the Semantic process-result expectation."
    )
  }

  /** Verifies service messages semantically and every plain line with the explicitly supplied contract. */
  final case class Hybrid(contract: SbtSemanticContract, plainOutput: PlainOutputContract)
    extends SbtOutputVerification {
    require(
      contract.plainOutput == PlainOutputContract.DelegatedToHybrid,
      "A Hybrid semantic contract must explicitly delegate plain output to the supplied Hybrid contract."
    )
    require(
      plainOutput != PlainOutputContract.DelegatedToHybrid,
      "Hybrid verification requires a concrete plain-output contract."
    )
    require(
      contract.processResult == ProcessResultContract.DelegatedToHarness,
      "The outer integration-test scenario owns the Hybrid process-result expectation."
    )
  }
}

/**
 * Runtime-profile selection is declarative and independent of observed output. An override is suitable for an exact
 * end-to-end canary at one explicit profile/scenario coordinate while other profiles use the default mode.
 */
private[logger] final case class SbtOutputVerificationSelection(
  defaultMode: SbtOutputVerification = SbtOutputVerification.ExactTranscript,
  runtimeProfileOverrides: Map[String, SbtOutputVerification] = Map.empty
) {
  runtimeProfileOverrides.keys.foreach(SbtOutputVerificationSelection.validateRuntimeProfile)

  def forRuntimeProfile(runtimeProfile: String): SbtOutputVerification = {
    SbtOutputVerificationSelection.validateRuntimeProfile(runtimeProfile)
    runtimeProfileOverrides.getOrElse(runtimeProfile, defaultMode)
  }
}

private[logger] object SbtOutputVerificationSelection {
  val ExactByDefault: SbtOutputVerificationSelection = SbtOutputVerificationSelection()

  /** Creates profile-specific exact-canary overrides for a scenario whose ordinary mode is semantic or hybrid. */
  def withExactCanaries(
    defaultMode: SbtOutputVerification,
    runtimeProfiles: String*
  ): SbtOutputVerificationSelection = {
    require(runtimeProfiles.nonEmpty, "At least one exact-canary runtime profile is required.")
    require(
      runtimeProfiles.distinct.size == runtimeProfiles.size,
      s"Duplicate exact-canary runtime profiles: ${runtimeProfiles.diff(runtimeProfiles.distinct).distinct.mkString(", ")}."
    )
    runtimeProfiles.foreach(validateRuntimeProfile)
    SbtOutputVerificationSelection(
      defaultMode,
      runtimeProfiles.iterator.map(_ -> SbtOutputVerification.ExactTranscript).toMap
    )
  }

  private val RuntimeProfilePattern = "[a-z0-9]+(?:[.-][a-z0-9]+)*"

  private[utils] def validateRuntimeProfile(runtimeProfile: String): Unit = require(
    runtimeProfile.matches(RuntimeProfilePattern),
    s"Invalid runtime output profile '$runtimeProfile'."
  )
}
