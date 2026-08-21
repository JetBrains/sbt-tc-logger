package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import org.junit.Assert.assertEquals
import org.junit.Test

class SbtObjectEventRendererTest {
  @Test def eventWithoutRenderedLinesIsSuppressed(): Unit =
    assertEquals(None, SbtObjectEventRenderer.combine(Nil))

  @Test def intentionalBlankLineIsPreserved(): Unit =
    assertEquals(Some(""), SbtObjectEventRenderer.combine(Seq("")))

  @Test def multipleRenderedLinesRemainOneMessage(): Unit =
    assertEquals(Some("first\nsecond"), SbtObjectEventRenderer.combine(Seq("first", "second")))
}
