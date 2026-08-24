// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog.compilation

/**
 * Provides supplementary warning and error totals for one TeamCity compiler flow
 *
 * It is used only when TeamCity renders structured compiler output rather than preserving the SBT console.
 * It makes the number of warnings and errors readily visible when a compiler node is expanded.
 * The feature is inspired by Zinc's `LoggedReporter.printSummary()`, but deliberately is not a byte-for-byte
 * reproduction: Zinc emits a total only when a compiler implementation invokes `printSummary()`
 * (the Scala compiler bridge does; Java diagnostics can be routed as `xsbti.Problem`s without such a call).
 * TeamCity instead gives every compiler flow the same local completion summary.
 * It is flow-local, not a build-wide aggregate.
 *
 * [[org.jetbrains.teamcity.plugins.sbt.logger.SbtCompilerProblemReporter]] routes diagnostics directly to TeamCity
 * and suppresses Zinc's formatted output. [[SbtCompilationReporter]] records every warning and error routed to a
 * flow, then emits at most one total for each severity immediately before `CompilationFinished`. The totals therefore
 * cover both Scala and Java diagnostics and count TeamCity's routed diagnostic events, rather than reproducing Zinc's
 * position de-duplication or compiler-specific timing.
 *
 * Information problems have no total. These supplementary lines do not determine compilation success, create
 * inspections, or replace detailed diagnostics. `SbtBuildLogMessageReporter` adds their `[warn]` and `[error]`
 * prefixes; this class supplies only the text and severity counts. Its methods are synchronized because SBT may
 * report problems concurrently for a flow.
 *
 * @note This feature is completely optional and representation-only, within each "Scala compiler" group
 */
private[compilation] final class CompilerProblemCounts {
  private var warnings = 0
  private var errors = 0

  def record(severity: xsbti.Severity): Unit = synchronized {
    severity match {
      case xsbti.Severity.Warn => warnings += 1
      case xsbti.Severity.Error => errors += 1
      case _ =>
    }
  }

  def warningSummary: Option[String] = synchronized {
    if (warnings > 0)
      Some(countElementsAsString(warnings, "warning") + " found")
    else
      None
  }

  def errorSummary: Option[String] = synchronized {
    if (errors > 0)
      Some(countElementsAsString(errors, "error") + " found")
    else
      None
  }

  private def countElementsAsString(count: Int, noun: String): String = {
    val quantity = count match {
      case 1 => "one"
      case 2 => "two"
      case 3 => "three"
      case 4 => "four"
      case _ => count.toString
    }
    s"$quantity $noun${if (count == 1) "" else "s"}"
  }
}
