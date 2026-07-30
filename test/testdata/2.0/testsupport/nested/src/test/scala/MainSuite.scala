


import org.scalatest._

class ASuite extends FunSuite {
  test("A should have ASCII value 41 hex") {
    assert('A' === 0x41)
  }
  test("a should have ASCII value 61 hex") {
    assert('a' === 0x61)
  }
}
class BSuite extends FunSuite {
  test("B should have ASCII value 42 hex") {
    assert('B' === 0x42)
  }
  test("b should have ASCII value 62 hex") {
    assert('b' === 0x62)
  }
}
class CSuite extends FunSuite {
  test("C should have ASCII value 43 hex") {
    assert('C' === 0x43)
  }
  test("c should have ASCII value 63 hex") {
    assert('c' === 0x63)
  }
}

class ASCIISuite extends Suites(
  new ASuite,
  new BSuite,
  new CSuite
)