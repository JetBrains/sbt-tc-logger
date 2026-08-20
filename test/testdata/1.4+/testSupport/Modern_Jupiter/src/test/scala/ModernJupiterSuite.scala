import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.{Disabled, Test}

class ModernJupiterSuite {
  @Test def passingTest(): Unit = assertEquals(2, 1 + 1)

  @Test def failingTest(): Unit = assertEquals(2, 1, "intentional Jupiter failure")

  @Disabled @Test def ignoredTest(): Unit = assertEquals(1, 2)
}
