class ModernMUnitSuite extends munit.FunSuite {
  test("passing test") {
    assertEquals(1 + 1, 2)
  }

  test("failing test") {
    assertEquals(1, 2)
  }

  test("ignored test".ignore) {
    fail("ignored body must not execute")
  }

  override def munitAnsiColors: Boolean = false
}
