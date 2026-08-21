// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

/** SBT 1 compatibility boundary for APIs that differ from the SBT 2 target. */
object SbtApiAdapter {
  /** SBT 1 has no build-wide task-result cache to opt out of. */
  def uncached[T](value: T): T = value
}
