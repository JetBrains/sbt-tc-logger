// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import _root_.sbt.std.TaskExtra.singleInputTask
import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.{SbtCoursierDependencyEventReporter, SbtDependencyResolutionReporter}
import sbt.Keys.{csrLogger, logLevel, update}
import sbt.{Configuration, Def, Extracted, ProjectRef, State}
import sbt.{Compile, Test, inConfig}
import sbt.librarymanagement.UpdateReport
import sbt.util.Level

object SbtDetailedDependencyResolutionSettings {
  private[logger] def reportCacheHit(
    dependencyResolutionReporter: () => SbtDependencyResolutionReporter,
    projectName: String,
    configuration: String
  )(report: UpdateReport): UpdateReport = {
    if (report.stats.cached) dependencyResolutionReporter().reportCacheHit(projectName, configuration)
    report
  }
}

/** Installs opt-in detailed Coursier dependency-resolution reporting for supported SBT targets. */
final class SbtDetailedDependencyResolutionSettings(
  dependencyResolutionReporter: => SbtDependencyResolutionReporter,
  detailedDependencyResolution: Boolean
) {
  def settings(
    projectRef: ProjectRef,
    projectName: String,
    extracted: Extracted,
    state: State
  ): Seq[Def.Setting[?]] =
    if (!detailedDependencyResolution || !SbtApiAdapter.isCoursierEnabled(extracted, projectRef)) Nil
    else dependencyResolutionConfigurations.foldLeft(Seq.empty[Def.Setting[?]]) { (allSettings, configuration) =>
      if (debugUpdateLogLevel(extracted, state, projectRef, configuration)) allSettings
      else allSettings ++ settingsForConfiguration(projectName, configuration)
    }

  private def settingsForConfiguration(
    projectName: String,
    configuration: Option[Configuration]
  ): Seq[Def.Setting[?]] = {
    val configurationName = configuration.fold("global")(_.name)
    val settings: Seq[Def.Setting[?]] = Seq(
      detailedDependencyResolutionUpdateSetting(projectName, configurationName),
      csrLogger.toSettingKey ~= { _ =>
        _root_.sbt.std.TaskExtra.task(Some(new SbtCoursierDependencyEventReporter(dependencyResolutionReporter, projectName, configurationName)))
      }
    )
    configuration.fold(settings)(config => inConfig(config)(settings))
  }

  private def debugUpdateLogLevel(
    extracted: Extracted,
    state: State,
    projectRef: ProjectRef,
    configuration: Option[Configuration]
  ): Boolean = {
    val level = SbtApiAdapter.updateLogLevel(extracted, projectRef, configuration)
    level.orElse(state.get(logLevel.key)).contains(Level.Debug)
  }

  private def detailedDependencyResolutionUpdateSetting(
    projectName: String,
    configuration: String
  ): Def.Setting[?] =
    update.toSettingKey ~= { original =>
      original
        .dependsOn(_root_.sbt.std.TaskExtra.task(dependencyResolutionReporter.started()))
        // Deliberately use `map` via the `singleInputTask` compatibility API, rather than SBT 2's `mapN`, to keep this implementation easy to cross-compile with SBT 1.
        .map(SbtDetailedDependencyResolutionSettings.reportCacheHit(
          () => dependencyResolutionReporter,
          projectName,
          configuration
        ))
        .andFinally(dependencyResolutionReporter.finished())
    }

  private val dependencyResolutionConfigurations: Seq[Option[Configuration]] = Seq(None, Some(Compile), Some(Test))
}
