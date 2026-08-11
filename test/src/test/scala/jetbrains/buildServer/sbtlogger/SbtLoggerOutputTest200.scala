package jetbrains.buildServer.sbtlogger

import org.junit.{Ignore, Test}

/** Runs the established SBT 1.x integration scenarios against SBT 2.0.4. */
class SbtLoggerOutputTest200 extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt200) {
  @Ignore("disabled in the SBT 1.x suite; preserved for SBT 2.x coverage")
  @Test
  override def testScalaTest(): Unit = ()

  @Ignore("no active SBT 1.x coverage exists for this scenario")
  @Test
  override def testNoSbtFileInProject(): Unit = ()

  @Ignore("disabled in the SBT 1.x suite; preserved for SBT 2.x coverage")
  @Test
  override def testWarningInTestOutput(): Unit = ()

  @Ignore("no active SBT 1.x coverage exists for this scenario")
  @Test
  override def testOtherSbtVersions(): Unit = ()

  @Ignore("disabled in the SBT 1.x suite; preserved for SBT 2.x coverage")
  @Test
  override def testIgnoredTest(): Unit = ()

  @Ignore("disabled in the SBT 1.x suite; preserved for SBT 2.x coverage")
  @Test
  override def testNestedSuites(): Unit = ()

  @Ignore("disabled in the SBT 1.x suite; preserved for SBT 2.x coverage")
  @Test
  override def testSpec2(): Unit = ()

  @Ignore("disabled in the SBT 1.x suite; preserved for SBT 2.x coverage")
  @Test
  override def testTW50753_initErrorInTests(): Unit = ()
}
