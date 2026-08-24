import org.junit.Test

class ForkedChildFailureTest {
  @Test def terminatesTheForkedJvm(): Unit = System.exit(255)
}
