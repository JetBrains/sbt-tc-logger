// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import org.jetbrains.teamcity.plugins.sbt.logger.buildLog.{SbtCoursierDependencyEventReporter, SbtDependencyResolutionReporter}
import sbt.Keys.*
import sbt.{Def, *}
import sbt.util.Level

/**
 * Installs opt-in detailed Coursier dependency-resolution reporting for SBT 2.
 *
 * This replaces Coursier's native Debug-only `downloaded URL` callback while detailed normal-mode reporting is active.
 */
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
    if (!detailedDependencyResolution) Nil
    else {
      val global = if (!debugUpdateLogLevel(extracted, state, projectRef, None)) settingsFor(projectName, "global") else Nil
      val compile = if (!debugUpdateLogLevel(extracted, state, projectRef, Some(Compile))) inConfig(Compile)(settingsFor(projectName, Compile.name)) else Nil
      val test = if (!debugUpdateLogLevel(extracted, state, projectRef, Some(Test))) inConfig(Test)(settingsFor(projectName, Test.name)) else Nil
      global ++ compile ++ test
    }

  private def settingsFor(projectName: String, configuration: String): Seq[Def.Setting[?]] = Seq(
    update.toSettingKey ~= { original =>
      original
        .dependsOn(_root_.sbt.std.TaskExtra.task(dependencyResolutionReporter.started()))
        .mapN { report =>
          if (report.stats.cached) dependencyResolutionReporter.reportCacheHit(projectName, configuration)
          report
        }
        .andFinally(dependencyResolutionReporter.finished())
    },
    csrLogger.toSettingKey ~= { original =>
      _root_.sbt.std.TaskExtra.task(Some(new SbtCoursierDependencyEventReporter(dependencyResolutionReporter, projectName, configuration)))
    }
  )

  private def debugUpdateLogLevel(
    extracted: Extracted,
    state: State,
    projectRef: ProjectRef,
    configuration: Option[Configuration]
  ): Boolean = {
    val level = configuration match {
      case Some(config) => extracted.getOpt(projectRef / config / update / logLevel)
      case None => extracted.getOpt(projectRef / update / logLevel)
    }
    level.orElse(state.get(logLevel.key)).contains(Level.Debug)
  }
}
