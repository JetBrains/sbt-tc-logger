// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.SbtBuildEventReporter
import org.jetbrains.teamcity.plugins.sbt.logger.serviceMessages.TeamCityServiceMessageWriter
import sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal.SbtPrivateKeys
import sbt.Def

/** SBT 1 compatibility boundary for APIs that differ from the SBT 2 target. */
object SbtApiAdapter {
  def reporterSettings(
    buildEventReporter: SbtBuildEventReporter,
    writer: TeamCityServiceMessageWriter,
    flowId: String,
    ensureCompilationStarted: () => Unit,
    reportCompilerOutput: Boolean
  ): Def.Setting[?] = {
    import _root_.sbt.Keys.compile
    compile / SbtPrivateKeys.compilerReporter := {
      val defaultReporter = (compile / SbtPrivateKeys.compilerReporter).value
      new SbtCompilerProblemReporter(defaultReporter, buildEventReporter, writer, flowId, ensureCompilationStarted, reportCompilerOutput)
    }
  }

}
