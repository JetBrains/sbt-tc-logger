package org.jetbrains.sbt.integrationTests

import org.jetbrains.sbt.integrationTests.FileUtils.normalisedAbsolutePath

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
    sbtCommands: Seq[String],
    envVars: Seq[String],
    verbose: Boolean,
    errorsExpected: Boolean,
    diagnosticLineNormaliser: String => String = identity
  ): ProcessRunResult = {
    val commandsFile = FileUtils.createTempFile("sbt-commands", ".lst")

    val sbtCommandsText = sbtCommands.mkString("\n")
    FileUtils.writeLinesTo(
      commandsFile,
      sbtCommandsText.linesIterator.toSeq *
    )

    println(
      s"""SBT process command line:
         |${indented((commandLinePrefix :+ s"< ${normalisedAbsolutePath(commandsFile)}").mkString("\n"), spaces = 4)}
         |
         |SBT process environment variables:
         |${indented(envVars.mkString("\n"), spaces = 4)}
         |
         |SBT commands file content:
         |${indented(sbtCommandsText.linesIterator.map("  " + _).mkString("\n"), spaces = 4)}
         |""".stripMargin
    )

    runProcess(commandLinePrefix, commandsFile, projectDir, envVars, verbose, errorsExpected, diagnosticLineNormaliser)
  }

  private def runProcess(
    commands: Seq[String],
    commandsFile: File,
    directory: File,
    envVars: Seq[String],
    verbose: Boolean,
    errorsExpected: Boolean,
    diagnosticLineNormaliser: String => String
  ): ProcessRunResult = {
    val builder = new ProcessBuilder(commands*)
    builder.directory(directory)
    builder.redirectInput(commandsFile)
    val environment = builder.environment()
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
