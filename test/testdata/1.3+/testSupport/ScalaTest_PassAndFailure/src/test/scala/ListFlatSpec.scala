

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ListFlatSpec extends AnyFlatSpec with Matchers {
  val xs = List(1, 2, 3)

  "A List" should "have length as count of elements in it" in {
    xs.length should be (3)
  }

  it should "contains elements passed in the factory method" in {
    xs.contains(1) should be (false)
    xs.contains(2) should be (true)
    xs.contains(3) should be (true)
  }

  it should "throw IndexOutOfBounds exception when index is out of bounds" in {
    assertThrows[IndexOutOfBoundsException] {
      xs(4)
    }
  }
}
