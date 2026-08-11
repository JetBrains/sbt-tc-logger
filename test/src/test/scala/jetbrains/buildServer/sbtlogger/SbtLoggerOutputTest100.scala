package jetbrains.buildServer.sbtlogger

import org.junit.{Ignore, Test}

/**
 * Runs logger-output integration scenarios against the sbt 1.x runtime.
 *
 * Most scenarios are inherited from [[SbtLoggerOutputTestsCommon]]. Runtime-specific overrides keep the historical sbt
 * 1.x coverage explicit when a fixture was intentionally disabled or had no active counterpart before the suites were
 * unified.
 */
class SbtLoggerOutputTest100 extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt100) {
  // The removed fixture launched SBT 0.13.15, but this plugin now ships only SBT 1.x and 2.x artifacts.
  @Ignore("otherVersions requires retired SBT 0.13.15 coverage")
  @Test
  override def testOtherSbtVersions(): Unit = ()

  // SBT 1 emits only a raw ExceptionInInitializerError, so the SBT 0.13 testFailed/suite-close expectation cannot be met.
  @Ignore("SBT 1 does not expose initialization-abort test events")
  @Test
  override def testTW50753_initErrorInTests(): Unit = ()
}
