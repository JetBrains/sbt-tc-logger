// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger.buildLog.compilation

sealed trait SbtCompilationConfiguration {
  def sbtConfigurationName: String
  def compilerDisplayName: String
}

object SbtCompilationConfiguration {
  case object Main extends SbtCompilationConfiguration {
    override val sbtConfigurationName: String = "compile"
    override val compilerDisplayName: String = "Scala compiler"
  }

  case object Test extends SbtCompilationConfiguration {
    override val sbtConfigurationName: String = "test"
    override val compilerDisplayName: String = "Scala compiler in Test"
  }
}
