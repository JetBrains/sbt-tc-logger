package org.jetbrains.sbt.integrationTests

import org.junit.{Assert, Test}

class SbtProcessRunnerTest {

  @Test
  def buildCommandLinePlacesOptionsBeforeIndividualCommandArguments(): Unit = {
    val commandLine = SbtProcessRunner.buildCommandLine(
      commandLinePrefix = Seq("java", "-jar", "sbt-launch.jar"),
      sbtOptions = Seq("--error", "-Dsbt.log.noformat=true"),
      sbtCommands = Seq("apply -cp logger.jar", "compile")
    )

    Assert.assertEquals(
      Seq(
        "java",
        "-jar",
        "sbt-launch.jar",
        "--error",
        "-Dsbt.log.noformat=true",
        "apply -cp logger.jar",
        "compile"
      ),
      commandLine
    )
  }

  @Test
  def processBuilderMergesStdoutAndStderrInWriteOrder(): Unit = {
    val result = SbtProcessRunner.runProcess(
      commands = Seq("/bin/sh", "-c", "printf 'out-1\\n'; printf 'err-1\\n' >&2; printf 'out-2\\n'"),
      directory = new java.io.File("."),
      envVars = Seq.empty,
      environmentVariablesToRemove = Seq.empty,
      verbose = false,
      errorsExpected = true,
      diagnosticLineNormaliser = identity
    )

    Assert.assertEquals(0, result.exitCode)
    Assert.assertEquals("out-1\nerr-1\nout-2\n", result.processOutput)
  }
}
