// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.reporting

/**
 * Best-effort TeamCity Tests-tab presentation for an SBT test suite that aborts with
 * `ExceptionInInitializerError` while it is being constructed.
 *
 * SBT normally reports test execution through [[sbt.TestReportListener]]: it starts a group, publishes test
 * events, and ends the group with either a result or a `Throwable`.  For this particular failure SBT starts the
 * group, but the `LinkageError` escapes before it publishes an event or calls either `endGroup` overload.  The
 * listener therefore knows the started suite and its TeamCity flow, while the only later diagnostic available to
 * this plugin is formatted SBT error output.  This reporter recognises the historical `Could not run test` form
 * and the current constructor-stack-frame form, then asks [[SbtTestReportListener]] to synthesize a failed test
 * and finish the otherwise abandoned suite.
 *
 * `ExceptionInInitializerError` is the JVM wrapper used when class initialisation fails: for example, an eager
 * Scala `object` value, a Java static initializer, a configuration lookup, a resource/native-library load, or
 * framework registration throws while the class is first used.  A suite constructor frequently triggers that
 * first use, so the original exception is exposed as this `LinkageError` with its root cause attached.  It is not
 * inherently a test assertion failure and does not identify a test method; the most accurate structured outcome
 * is a failed or aborted suite.
 *
 * SBT does not handle this error specially.  Its test runner converts `NonFatal` exceptions, `NoClassDefFoundError`,
 * and `IllegalAccessError` into typed test events, but `ExceptionInInitializerError` extends `LinkageError` and
 * therefore escapes those branches.  This is why the normal listener protocol is incomplete for this case.  The
 * explicit `NoClassDefFoundError` branch was added by SBT to diagnose class-loader layering problems; it is not a
 * general contract for all linkage errors.  Other errors such as `NoSuchMethodError`, `BootstrapMethodError`, and
 * `IncompatibleClassChangeError` can have the same missing-terminal-callback characteristic.
 *
 * This class nevertheless recognises only `ExceptionInInitializerError` because it was the concrete, historically
 * reported suite-abort case and had a recognizable SBT diagnostic.  That narrow scope limits false test reports:
 * other linkage errors can occur before a suite starts, in SBT/build code, or with no reliable suite identity.
 * Adding more error-name or stack-trace parsers would not create a proper contract and would increase incorrect
 * attribution.  A complete solution belongs in SBT, which can call `endGroup` with the real `Throwable` before it
 * rethrows an escaping linkage error.
 *
 * This is deliberately a presentation compatibility layer.  It does not change SBT task evaluation, catch or
 * rethrow the initializer error, alter `Test / test`.result, or determine the launcher exit code.  With the
 * failure-preserving `TestResultLogger.Default.copy` configuration, ordinary failed tests still fail through
 * SBT's normal result semantics; an initializer error that escapes SBT also makes the outer SBT process fail
 * without this reporter.  Without this class, TeamCity still receives the original build-log error and the build
 * still fails, but the Tests tab may contain an unclosed started suite and no structured failure for it.
 *
 * The workaround originated in the 2017 fix for TW-50753, now tracked as GitHub
 * [[https://github.com/JetBrains/sbt-tc-logger/issues/12 #12]].  The original parser was introduced by
 * [[https://github.com/JetBrains/sbt-tc-logger/commit/e75f78d314c0456406cca52f83aa1654f90116c e75f78d]];
 * modern SBT 1.12 and SBT 2 output required the lifecycle-aware extension in
 * [[https://github.com/JetBrains/sbt-tc-logger/commit/0f861ec35fe8bb83bc7bc0b689027963be52beda 0f861ec3]].
 * It is related to
 * [[https://github.com/JetBrains/sbt-tc-logger/issues/9 #9]], which exposed the older no-op result logger's
 * incorrect success semantics.  The latter is addressed separately by preserving SBT's default result behaviour;
 * this reporter must not become a substitute for that contract.
 *
 * Because the suite identity is inferred from rendered text rather than supplied by SBT, it is not authoritative:
 * a framework may change the wording or stack layout, and a constructor frame may name a helper rather than the
 * suite.  Keep the ordinary SBT error and non-zero result as the correctness contract; treat the messages emitted
 * here as an optional enhancement until SBT exposes a typed terminal callback for this case.
 */
final class SbtInitializerErrorTestFailureReporter(testReportListener: SbtTestReportListener) {
  def reportIfInitializerError(message: String, flowId: String): Unit = {
    val suffix = "java.lang.ExceptionInInitializerError"
    if (message.indexOf(suffix) > -1) {
      suiteName(message).foreach { name =>
        testReportListener.finishInitializerFailure(name, message, flowId)
      }
    }
  }

  private def suiteName(message: String): Option[String] = {
    val prefix = "Could not run test"
    val prefixIndex = message.indexOf(prefix)
    if (prefixIndex > -1) {
      val name = message.substring(prefixIndex + prefix.length, message.indexOf("java.lang.ExceptionInInitializerError"))
        .trim()
        .stripSuffix(":")
        .trim()
      if (name.nonEmpty) Some(name) else None
    } else {
      "(?m)(?:^|\\R)(?:\\[error\\]\\s*)?\\s*at ([A-Za-z0-9_$.]+)\\.<init>\\(".r
        .findFirstMatchIn(message)
        .map(_.group(1))
    }
  }
}
