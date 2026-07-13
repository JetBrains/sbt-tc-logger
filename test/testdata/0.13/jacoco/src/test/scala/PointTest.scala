

import org.scalatest.junit.AssertionsForJUnit
import org.junit.Assert._
import org.junit._

class PointTest extends AssertionsForJUnit  {

  @Test
  def verifySomethingTest() {
     val point = new Point(1,1)
     point.move(2,2)
     assert(3 === point.x)
     assert(3 === point.y)
  }


}