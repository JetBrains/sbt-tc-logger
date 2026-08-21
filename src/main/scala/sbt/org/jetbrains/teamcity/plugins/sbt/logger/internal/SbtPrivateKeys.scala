// Copyright © 2013–2026 JetBrains s.r.o.
package sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal

/** The isolated bridge to SBT APIs declared `private[sbt]`. */
object SbtPrivateKeys {

  val compilerReporter = sbt.Keys.compilerReporter

}
