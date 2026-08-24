// Copyright © 2013–2026 JetBrains s.r.o.
package org.jetbrains.teamcity.plugins.sbt.logger

import sbt.Def
import sbt.Keys.{test, testFull, testOnly, testQuick, testSelected}
import sbt.internal.util.AttributeKey

/** SBT 2 compatibility boundary for APIs that differ from the SBT 1 target. */
object SbtApiAdapter {
  inline def uncached[T](inline value: T): T = Def.uncached(value)

  val testTaskKeys: Set[AttributeKey[?]] = Set(test.key, testOnly.key, testSelected.key, testQuick.key, testFull.key)
}
