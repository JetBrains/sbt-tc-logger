import org.junit.Assert.fail
import org.junit.Test

class FailedTest {
  @Test def fails(): Unit = fail("#9 direct task-result fixture")
}
