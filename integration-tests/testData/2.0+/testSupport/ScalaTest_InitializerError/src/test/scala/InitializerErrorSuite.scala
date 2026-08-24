import org.scalatest.FunSuite

class InitializerErrorSuite extends FunSuite {
  private val initialized: Unit = InitializerErrorFixture.initialize()

  test("unreachable") {
    fail("The suite constructor must fail before this test starts")
  }
}

object InitializerErrorFixture {
  private val failure: Unit = throw new IllegalStateException("Suite construction failure for #12")

  def initialize(): Unit = ()
}
