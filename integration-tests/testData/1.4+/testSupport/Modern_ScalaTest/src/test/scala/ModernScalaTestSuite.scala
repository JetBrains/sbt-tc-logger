import org.scalatest.funsuite.AnyFunSuite

class ModernScalaTestSuite extends AnyFunSuite {
  test("passing test") {
    assert(1 + 1 == 2)
  }

  test("failing test") {
    fail("intentional ScalaTest failure")
  }

  ignore("ignored test") {
    fail("ignored body must not execute")
  }
}
