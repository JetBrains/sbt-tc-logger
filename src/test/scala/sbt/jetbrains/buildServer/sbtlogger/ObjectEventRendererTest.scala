package sbt.jetbrains.buildServer.sbtlogger

import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectEventRendererTest {
  @Test def eventWithoutRenderedLinesIsSuppressed(): Unit =
    assertEquals(None, ObjectEventRenderer.combine(Nil))

  @Test def intentionalBlankLineIsPreserved(): Unit =
    assertEquals(Some(""), ObjectEventRenderer.combine(Seq("")))

  @Test def multipleRenderedLinesRemainOneMessage(): Unit =
    assertEquals(Some("first\nsecond"), ObjectEventRenderer.combine(Seq("first", "second")))
}
