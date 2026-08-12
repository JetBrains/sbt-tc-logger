

import org.scalatest.{FlatSpec, Matchers}

class ListFlatSpec extends FlatSpec with Matchers {
  val xs = List(1, 2, 3)

  "A List" should "have length as count of elements in it" in {
    xs.length shouldBe 3
  }

  it should "contains elements passed in the factory method" in {
    xs.contains(1) shouldBe false
    xs.contains(2) shouldBe true
    xs.contains(3) shouldBe true
  }

  it should "throw IndexOutOfBounds exception when index is out of bounds" in {
    intercept[IndexOutOfBoundsException] {
      xs(4)
    }
  }
}
