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
  @Ignore("disabled in the previous sbt 1.x suite; preserved explicitly during suite unification")
  @Test
  override def testScalaTest(): Unit = ()

  @Ignore("no active sbt 1.x coverage existed before suite unification")
  @Test
  override def testNoSbtFileInProject(): Unit = ()

  @Ignore("disabled in the previous sbt 1.x suite; preserved explicitly during suite unification")
  @Test
  override def testWarningInTestOutput(): Unit = ()

  @Ignore("no active sbt 1.x coverage existed before suite unification")
  @Test
  override def testOtherSbtVersions(): Unit = ()

  @Ignore("disabled in the previous sbt 1.x suite; preserved explicitly during suite unification")
  @Test
  override def testIgnoredTest(): Unit = ()

  @Ignore("disabled in the previous sbt 1.x suite; preserved explicitly during suite unification")
  @Test
  override def testNestedSuites(): Unit = ()

  @Ignore("disabled in the previous sbt 1.x suite; preserved explicitly during suite unification")
  @Test
  override def testSpec2(): Unit = ()

  @Ignore("disabled in the previous sbt 1.x suite; preserved explicitly during suite unification")
  @Test
  override def testTW50753_initErrorInTests(): Unit = ()
}
