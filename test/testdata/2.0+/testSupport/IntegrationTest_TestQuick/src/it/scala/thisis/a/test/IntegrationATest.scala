package thisis.a.test

import org.junit.Assert.assertEquals
import org.junit.Test

class IntegrationATest {
  @Test
  def testPasses: Unit = assertEquals(1, 1)

  @Test
  def testFails: Unit = assertEquals("integration test failure", 3, 1)
}
