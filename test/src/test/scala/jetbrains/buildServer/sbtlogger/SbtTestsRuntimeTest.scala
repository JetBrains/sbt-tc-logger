package jetbrains.buildServer.sbtlogger

import org.junit.{Assert, Test}

class SbtTestsRuntimeTest {

  @Test
  def forSbtVersionDerivesSbt1Metadata(): Unit = {
    assertRuntime(
      SbtTestsRuntime.forSbtVersion("1.0.0"),
      id = "1.0.0",
      testDataRelativePath = "test/testdata/1.0",
      sbtBinaryVersion = "1.0",
      launcherVersion = "1.12.15",
      commandTransport = SbtCommandTransport.StandardInput
    )
  }

  @Test
  def forSbtVersionDerivesSbt2Metadata(): Unit = {
    assertRuntime(
      SbtTestsRuntime.forSbtVersion("2.0.4"),
      id = "2.0.4",
      testDataRelativePath = "test/testdata/2.0",
      sbtBinaryVersion = "2",
      launcherVersion = "2.0.6",
      commandTransport = SbtCommandTransport.CommandArgument
    )
  }

  @Test
  def forSbtVersionAcceptsPrereleaseVersions(): Unit = {
    Assert.assertEquals("1.12.15", SbtTestsRuntime.forSbtVersion("1.13.0-RC1").launcherVersion)
    Assert.assertEquals("2.0.6", SbtTestsRuntime.forSbtVersion("2.1.0-M2").launcherVersion)
  }

  @Test
  def forSbtVersionRejectsMalformedAndUnsupportedVersions(): Unit = {
    Seq("", "not-a-version", "0.13.0", "3.0.0").foreach { version =>
      val error = expectIllegalArgumentException {
        SbtTestsRuntime.forSbtVersion(version)
      }
      Assert.assertTrue(error.getMessage.contains("supported lines are 1.x and 2.x"))
    }
  }

  private def assertRuntime(
    runtime: SbtTestsRuntime,
    id: String,
    testDataRelativePath: String,
    sbtBinaryVersion: String,
    launcherVersion: String,
    commandTransport: SbtCommandTransport
  ): Unit = {
    Assert.assertEquals(id, runtime.id)
    Assert.assertEquals(testDataRelativePath, runtime.testDataRelativePath)
    Assert.assertEquals(sbtBinaryVersion, runtime.sbtBinaryVersion)
    Assert.assertEquals(launcherVersion, runtime.launcherVersion)
    Assert.assertEquals(commandTransport, runtime.commandTransport)
  }

  private def expectIllegalArgumentException(block: => Unit): IllegalArgumentException = {
    try {
      block
    } catch {
      case error: IllegalArgumentException => return error
    }

    Assert.fail("Expected IllegalArgumentException")
    throw new AssertionError("unreachable")
  }
}
