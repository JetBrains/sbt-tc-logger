package org.jetbrains.teamcity.plugins.sbt.logger.buildLog

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderedLinesTest {
  @Test def eventWithoutRenderedLinesIsSuppressed(): Unit =
    assertEquals(None, RenderedLines.toMultilineStringIfAny(Nil))

  @Test def intentionalBlankLineIsPreserved(): Unit =
    assertEquals(Some(""), RenderedLines.toMultilineStringIfAny(Seq("")))

  @Test def multipleRenderedLinesRemainOneMessage(): Unit =
    assertEquals(Some("first\nsecond"), RenderedLines.toMultilineStringIfAny(Seq("first", "second")))
}
