package org.jetbrains.sbt.integrationTests

import java.io.File
import scala.io.Source
import scala.util.Try

/**
 * @note copied from sbt-structure `org.jetbrains.sbt.integrationTests.utils.SbtProcessRunner` with some adoptations
 */
object SbtProcessRunner {

  case class ProcessRunResult(exitCode: Int, processOutput: String)

  def runSbtProcess(
    projectDir: File,
    commandLinePrefix: Seq[String],
    sbtOptions: Seq[String],
    sbtCommands: Seq[String],
    envVars: Seq[String],
    verbose: Boolean,
    errorsExpected: Boolean,
    diagnosticLineNormaliser: String => String = identity,
    environmentVariablesToRemove: Seq[String] = Seq.empty
  ): ProcessRunResult = {
    val sbtCommandsText = sbtCommands.mkString("\n")
    val commandLine = buildCommandLine(commandLinePrefix, sbtOptions, sbtCommands)

    println(
      s"""SBT process command line:
         |${indented(commandLine.mkString("\n"), spaces = 4)}
         |
         |SBT command arguments:
         |${indented(sbtCommandsText.linesIterator.map("  " + _).mkString("\n"), spaces = 4)}
         |
         |SBT process environment variables:
         |${indented(envVars.mkString("\n"), spaces = 4)}
         |
         |SBT process environment variables removed:
         |${indented(environmentVariablesToRemove.mkString("\n"), spaces = 4)}
         |""".stripMargin
    )

    runProcess(
      commandLine,
      projectDir,
      envVars,
      environmentVariablesToRemove,
      verbose,
      errorsExpected,
      diagnosticLineNormaliser
    )
  }

  /** Builds the non-interactive nested-sbt command line with exactly one trailing command argument. */
  private[integrationTests] def buildCommandLine(
    commandLinePrefix: Seq[String],
    sbtOptions: Seq[String],
    sbtCommands: Seq[String]
  ): Seq[String] =
    commandLinePrefix ++ sbtOptions :+ sbtCommands.mkString(";", ";", "")

  private def runProcess(
    commands: Seq[String],
    directory: File,
    envVars: Seq[String],
    environmentVariablesToRemove: Seq[String],
    verbose: Boolean,
    errorsExpected: Boolean,
    diagnosticLineNormaliser: String => String
  ): ProcessRunResult = {
    val builder = new ProcessBuilder(commands*)
    builder.directory(directory)
    val environment = builder.environment()
    // Remove inherited variables first so callers can model an absent value while still overriding other parent settings.
    environmentVariablesToRemove.foreach(environment.remove)
    envVars.foreach { envVar =>
      val splitIndex = envVar.indexOf('=')
      if (splitIndex > 0) {
        environment.put(envVar.substring(0, splitIndex), envVar.substring(splitIndex + 1))
      }
    }
    val process = builder.start()

    val processOutput: StringBuilder = new StringBuilder()

    val stdinThread = inThread {
      Source.fromInputStream(process.getInputStream).getLines().foreach { line =>
        processOutput.synchronized {
          processOutput.append(line).append("\n")
        }

        val hasError = line.startsWith("[error]")
        if (hasError && !errorsExpected) {
          System.err.println(line)
          process.destroy()
        }
        else if (verbose) {
          System.out.println(s"stdout: ${diagnosticLineNormaliser(line)}")
        }
      }
    }

    val stderrThread = inThread {
      Source.fromInputStream(process.getErrorStream).getLines().foreach { line =>
        processOutput.synchronized {
          processOutput.append(line).append("\n")
        }
        if (verbose) {
          System.err.println("stderr: " + line)
        }
      }
    }

    Runtime.getRuntime.addShutdownHook(new Thread((() => {
      if (process.isAlive) {
        System.err.print("Destroying dangling sbt process")
        Try(process.destroy())
      }
    }): Runnable, "terminate sbt process"))

    process.waitFor()

    stdinThread.join()
    stderrThread.join()

    ProcessRunResult(
      exitCode = process.exitValue(),
      processOutput = processOutput.toString
    )
  }

  private def inThread(block: => Unit): Thread = {
    val runnable = new Runnable {
      def run(): Unit = {
        block
      }
    }
    val thread = new Thread(runnable)
    thread.start()
    thread
  }

  private def indented(text: String, spaces: Int): String = {
    val indent = " " * spaces
    text.linesIterator.map(indent + _).mkString("\n")
  }
}
