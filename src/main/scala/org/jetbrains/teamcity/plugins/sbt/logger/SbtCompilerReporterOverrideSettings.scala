// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildEventReporter
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal.SbtPrivateKeys
import sbt.{Def, *}

/** Overrides SBT's compiler reporter in both supported SBT targets. */
object SbtCompilerReporterOverrideSettings {
  def settings(
    buildEventReporter: SbtBuildEventReporter,
    writer: TeamCityServiceMessageWriter,
    flowId: String,
    ensureCompilationStarted: () => Unit,
    reportCompilerOutput: Boolean
  ): Def.Setting[?] = {
    import _root_.sbt.Keys.compile
    compile / SbtPrivateKeys.compilerReporter := SbtApiAdapter.uncached {
      val defaultReporter = (compile / SbtPrivateKeys.compilerReporter).value
      new SbtCompilerProblemReporter(
        defaultReporter,
        buildEventReporter,
        writer,
        flowId,
        ensureCompilationStarted,
        reportCompilerOutput
      )
    }
  }
}
