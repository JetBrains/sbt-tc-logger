package jetbrains.buildServer.sbtlogger.utils

import org.junit.{Assert, Test}

class SbtFailurePropagationExpectationTest {

  @Test
  def notRequiredDoesNotAddAnSbtCommandOrRequireFailureEvidence(): Unit = {
    Assert.assertEquals(None, SbtFailurePropagationExpectation.setupCommand(SbtFailurePropagationExpectation.NotRequired))
    SbtFailurePropagationExpectation.assertObserved(SbtFailurePropagationExpectation.NotRequired, exitCode = 0, processOutput = "")
  }

  @Test
  def processExitNonZeroRequiresANonZeroExitCode(): Unit = {
    SbtFailurePropagationExpectation.assertObserved(
      SbtFailurePropagationExpectation.ProcessExitNonZero,
      exitCode = 1,
      processOutput = ""
    )

    expectAssertionError {
      SbtFailurePropagationExpectation.assertObserved(
        SbtFailurePropagationExpectation.ProcessExitNonZero,
        exitCode = 0,
        processOutput = ""
      )
    }
  }

  @Test
  def sbtOnFailureHandlerInstallsAndRequiresExactlyOneMarker(): Unit = {
    Assert.assertEquals(
      Some("onFailure eval println(\"SBT_TC_LOGGER_FAILURE_PROPAGATION_ON_FAILURE\")"),
      SbtFailurePropagationExpectation.setupCommand(SbtFailurePropagationExpectation.SbtOnFailureHandler)
    )

    SbtFailurePropagationExpectation.assertObserved(
      SbtFailurePropagationExpectation.SbtOnFailureHandler,
      exitCode = 0,
      processOutput = "[info] SBT_TC_LOGGER_FAILURE_PROPAGATION_ON_FAILURE\n"
    )

    expectAssertionError {
      SbtFailurePropagationExpectation.assertObserved(
        SbtFailurePropagationExpectation.SbtOnFailureHandler,
        exitCode = 0,
        processOutput = "SBT_TC_LOGGER_FAILURE_PROPAGATION_ON_FAILURE\nSBT_TC_LOGGER_FAILURE_PROPAGATION_ON_FAILURE\n"
      )
    }
  }

  private def expectAssertionError(block: => Unit): Unit = {
    try {
      block
    } catch {
      case _: AssertionError => return
    }

    Assert.fail("Expected an AssertionError, but the check completed successfully")
  }
}
