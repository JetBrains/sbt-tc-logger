// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog.compilation

/**
 * Describes one SBT compilation as it is presented in TeamCity.
 *
 * The compilation reporter uses this value for every lifecycle transition and compiler diagnostic so that a
 * concurrent compilation retains both one TeamCity flow and one stable compiler title.
 *
 * @param flowId TeamCity flow ID that groups this compilation's lifecycle and Build Log output
 * @param projectName SBT project name to append to the compiler title, when the compilation has a project scope
 * @param configuration main or test compilation, which selects the compiler-title variant
 */
final case class SbtCompilationFlow(
  flowId: String,
  projectName: Option[String],
  configuration: SbtCompilationConfiguration
) {
  /**
   * Produces the compiler title used by TeamCity `compilationStarted` and `compilationFinished` messages.
   *
   * For example:
   *  1. the main compilation of project `core` is `Scala compiler [core]`
   *  1. a test compilation without a project name is `Scala compiler in Test`.
   */
  def displayName: String = {
    val configurationName = configuration.compilerDisplayName
    projectName.fold(configurationName)(name => s"$configurationName [$name]")
  }
}
