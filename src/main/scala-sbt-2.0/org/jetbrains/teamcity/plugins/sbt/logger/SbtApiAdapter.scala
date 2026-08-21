// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import sbt.Def

/** SBT 2 compatibility boundary for APIs that differ from the SBT 1 target. */
object SbtApiAdapter {
  inline def uncached[T](inline value: T): T = Def.uncached(value)
}
