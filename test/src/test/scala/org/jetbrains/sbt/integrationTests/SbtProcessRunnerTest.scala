package org.jetbrains.sbt.integrationTests

import org.junit.{Assert, Test}

class SbtProcessRunnerTest {

  @Test
  def buildCommandLinePlacesOptionsBeforeIndividualCommandArguments(): Unit = {
    val commandLine = SbtProcessRunner.buildCommandLine(
      commandLinePrefix = Seq("java", "-jar", "sbt-launch.jar"),
      sbtOptions = Seq("--error", "-Dsbt.log.noformat=true"),
      sbtCommands = Seq("apply -cp logger.jar", "compile", "exit")
    )

    Assert.assertEquals(
      Seq(
        "java",
        "-jar",
        "sbt-launch.jar",
        "--error",
        "-Dsbt.log.noformat=true",
        "apply -cp logger.jar",
        "compile",
        "exit"
      ),
      commandLine
    )
  }
}
