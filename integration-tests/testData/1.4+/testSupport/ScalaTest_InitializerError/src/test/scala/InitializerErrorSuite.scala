import org.scalatest.funsuite.AnyFunSuite

class InitializerErrorSuite extends AnyFunSuite {
  private val initialized: Unit = InitializerErrorFixture.initialize()

  test("unreachable") {
    fail("The suite constructor must fail before this test starts")
  }
}

object InitializerErrorFixture {
  private val failure: Unit = throw new IllegalStateException("Suite construction failure for #12")

  def initialize(): Unit = ()
}
