package example

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomResultLoggerTest {
  @Test
  def failsButConfiguredResultLoggerDoesNotThrow(): Unit = assertEquals("intentional failure", 2, 1)
}
