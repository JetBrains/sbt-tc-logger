package jetbrains.buildServer.sbtlogger

import org.junit.Assume.assumeFalse
import org.junit.Before

/** Runs the shared legacy logger-output scenarios against SBT 1.0.0 on the exact JDK 8 baseline. */
class SbtLoggerOutputTest_1_0_0_Jdk8
  extends SbtLoggerOutputTestsCommon(SbtTestsRuntime.Sbt_1_0_0_Jdk8)
    with SbtLoggerOutputTests_Sbt1_0_JDK8 {

  @Before
  def requireX86RuntimeForLegacySbt(): Unit =
    assumeFalse(
      "SBT 1.0.0 requires x86_64 Java under Rosetta or an x86 CI agent on Apple Silicon hosts.",
      isNativeAppleSilicon
    )

  private def isNativeAppleSilicon: Boolean = {
    val osName = System.getProperty("os.name", "").toLowerCase
    val osArchitecture = System.getProperty("os.arch", "").toLowerCase
    osName.contains("mac") && Set("aarch64", "arm64").contains(osArchitecture)
  }
}
