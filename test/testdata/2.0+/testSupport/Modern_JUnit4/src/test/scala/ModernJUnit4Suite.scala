import org.junit.Assert.assertEquals
import org.junit.{Ignore, Test}

class ModernJUnit4Suite {
  @Test def passingTest(): Unit = assertEquals(2, 1 + 1)

  @Test def failingTest(): Unit = assertEquals("intentional JUnit 4 failure", 2, 1)

  @Ignore @Test def ignoredTest(): Unit = assertEquals(1, 2)
}
