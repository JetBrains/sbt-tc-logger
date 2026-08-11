package jetbrains.buildServer.sbtlogger

import org.junit.{Ignore, Test}

/** Runs the established SBT 1.x integration scenarios against SBT 2.0.4. */
class SbtLoggerOutputTest200 extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt200) {
  // The removed fixture launched SBT 0.13.15, but this plugin now ships only SBT 1.x and 2.x artifacts.
  @Ignore("otherVersions requires retired SBT 0.13.15 coverage")
  @Test
  override def testOtherSbtVersions(): Unit = ()

  // SBT 2 emits only a raw ExceptionInInitializerError, so the SBT 0.13 testFailed/suite-close expectation cannot be met.
  @Ignore("SBT 2 does not expose initialization-abort test events")
  @Test
  override def testTW50753_initErrorInTests(): Unit = ()
}
