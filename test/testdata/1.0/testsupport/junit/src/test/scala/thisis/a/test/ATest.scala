

package thisis.a.test


import org.junit.Assert._
import org.junit.Test

class ATest {

  @Test
  def testMe = assertEquals(1,1)

  @Test
  def testMeToo = assertEquals("comparing unequal ints is WRONG",3,1)

}