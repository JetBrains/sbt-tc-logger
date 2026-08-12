

package fixture.jacoco

import org.junit.Assert.assertEquals
import org.junit.Test

class PointTest {

  @Test
  def movesPoint(): Unit = {
     val point = new Point(1, 1)
     point.move(2, 2)
     assertEquals(3, point.x)
     assertEquals(3, point.y)
  }
}
