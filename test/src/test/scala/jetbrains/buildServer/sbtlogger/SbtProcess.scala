package jetbrains.buildServer.sbtlogger

import junit.framework.Assert

import java.io.{BufferedReader, File, FileReader, IOException, InputStreamReader}
import java.util.regex.Pattern
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

object SbtProcess {
  private val RepoRootProperty = "sbt.tc.repo.root"
  private val Sbt013LauncherProperty = "sbt.tc.sbt.launcher.013"
  private val Sbt100LauncherProperty = "sbt.tc.sbt.launcher.100"
  private val Plugin013Property = "sbt.tc.plugin.013"
  private val Plugin100Property = "sbt.tc.plugin.100"
  private val JavaHomeProperty = "sbt.tc.java.home"

  def runAndTest(sbtCommands: String, workingDir: String, outputFiles: String*): Int = {
    runSbtAndTest(applyPlugin = true, "--error", sbtCommands, workingDir, outputFiles*)
  }

  def runAndTestWithAdditionalParams(sbtCommands: String, params: String, workingDir: String, outputFiles: String*): Int = {
    runSbtAndTest(applyPlugin = true, params, sbtCommands, workingDir, outputFiles*)
  }

  def runWithoutApplyAndTest(sbtCommands: String, workingDir: String, outputFiles: String*): Int = {
    runSbtAndTest(applyPlugin = false, "--error", sbtCommands, workingDir, outputFiles*)
  }

  private def runSbtAndTest(
    applyPlugin: Boolean,
    params: String,
    sbtCommands: String,
    workingDir: String,
    outputFiles: String*
  ): Int = {
    val javaHome = requiredJavaHome()
    val javaBin = javaHome +
      File.separator + "bin" +
      File.separator + "java"

    val sbtGlobalBase = new File(repoRoot(), "test" + File.separator + "sbt").getAbsoluteFile
    val sbt100Test = isSbt100Test(workingDir)
    val sbtLauncherPath = requiredFile(if (sbt100Test) Sbt100LauncherProperty else Sbt013LauncherProperty).getAbsolutePath
    val sbtTcLoggerPluginPath = requiredFile(if (sbt100Test) Plugin100Property else Plugin013Property).getAbsolutePath

    val sbtGlobalBaseParam = "-Dsbt.global.base=" + sbtGlobalBase.getAbsolutePath
    val sbtLogParam = "-Dsbt.log.noformat=true"

    val applyCommand =
      if (applyPlugin) s"""apply -cp "$sbtTcLoggerPluginPath" jetbrains.buildServer.sbtlogger.SbtTeamCityLogger"""
      else ""
    val commands = sbtCommands.split(" ").toSeq
    val fullListOfCommands = ListBuffer[String](
      javaBin,
      "-Xmx512m",
      "-XX:MaxPermSize=256m",
      "-jar",
      sbtLauncherPath,
      sbtGlobalBaseParam,
      sbtLogParam
    )
    addIfNotBlank(fullListOfCommands, applyCommand)
    addIfNotBlank(fullListOfCommands, params)
    fullListOfCommands ++= commands
    val builder = new ProcessBuilder(fullListOfCommands.toSeq*)

    val env = builder.environment()
    env.put("TEAMCITY_VERSION", "9.0.TEST")
    env.put("JAVA_HOME", javaHome)
    env.put("SBT_HOME", sbtGlobalBase.getAbsolutePath)

    val path = env.get("PATH")
    var jHome = System.getenv("JDK_HOME")
    if (jHome == null) {
      jHome = javaHome
    }
    env.put("PATH", (jHome + File.separator + "bin") + (if (path != null && path.nonEmpty) File.pathSeparator + path else ""))

    if (params.contains("--debug")) {
      println("builder.environment()")
      for (entry <- env.entrySet().asScala) {
        println(entry.getKey + " -> " + entry.getValue)
      }
    }

    builder.directory(new File(workingDir))
    val process = builder.start()
    val stdInput = new BufferedReader(new InputStreamReader(process.getInputStream))
    val stdError = new BufferedReader(new InputStreamReader(process.getErrorStream))

    val excludes = new File(workingDir + File.separator + "excludes.txt")
    val brExcludes =
      if (excludes.exists()) new BufferedReader(new FileReader(excludes))
      else null

    val effectiveOutputFiles =
      if (outputFiles == null || outputFiles.isEmpty) Seq("output.txt")
      else outputFiles

    val readers = effectiveOutputFiles.map { outputFile =>
      new BufferedReader(new FileReader(workingDir + File.separator + outputFile))
    }
    checkOutputTest(stdInput, brExcludes, readers*)

    process.waitFor()

    println("Here is the standard error of the command (if any):\n")
    var errorLine = stdError.readLine()
    while (errorLine != null) {
      println(errorLine)
      errorLine = stdError.readLine()
    }

    process.exitValue()
  }

  @throws[IOException]
  def checkOutputTest(stdInput: BufferedReader, brExcludes: BufferedReader, requiredOutput: BufferedReader*): Unit = {
    val allLines = ListBuffer.empty[String]
    val excludes = getPatterns(brExcludes)
    val excludesFound = ListBuffer.empty[String]

    var line = stdInput.readLine()
    while (line != null) {
      println(line.replaceAll("##teamcity", "##t-e-a-m-c-i-t-y"))
      allLines += line
      for (exclude <- excludes) {
        val excludeMatcher = exclude.matcher(line)
        if (excludeMatcher.find()) {
          excludesFound += line
        }
      }
      line = stdInput.readLine()
    }

    if (brExcludes != null && excludes.nonEmpty && excludesFound.nonEmpty) {
      println("===================== ERROR ==========================")
      println("The following lines were found but should not be there:")
      for (excludeFound <- excludesFound) {
        println(excludeFound)
      }
      Assert.assertEquals(excludesFound.size, 0)
    }

    for (reader <- requiredOutput) {
      var i = 0
      var found = 0

      println("=== Check file ===")
      val required = getPatterns(reader)
      var currentRequired = required(i)
      i += 1

      for (line <- allLines) {
        val matcher = currentRequired.matcher(line)
        if (matcher.find()) {
          found += 1
          if (i < required.size) {
            currentRequired = required(i)
            i += 1
          }
        }
      }

      if (found != required.size) {
        println("First failed line:")
        println(currentRequired)
      }
      Assert.assertEquals(required.size, found)
    }
  }

  @throws[IOException]
  private def getPatterns(requiredOutput: BufferedReader): Seq[Pattern] = {
    if (requiredOutput == null) {
      Seq.empty
    }
    else {
      val required = ListBuffer.empty[Pattern]
      var line = requiredOutput.readLine()
      while (line != null) {
        required += Pattern.compile(line)
        line = requiredOutput.readLine()
      }
      required.toSeq
    }
  }

  def repoRoot(): File = {
    var repoRoot = System.getProperty(RepoRootProperty)
    if (repoRoot == null || repoRoot.trim.isEmpty) {
      repoRoot = "."
    }
    new File(repoRoot).getAbsoluteFile
  }

  private def requiredFile(propertyName: String): File = {
    val path = System.getProperty(propertyName)
    if (path == null || path.trim.isEmpty) {
      throw new IllegalStateException("Missing required system property: " + propertyName)
    }

    val file = new File(path)
    if (!file.isFile) {
      throw new IllegalStateException("Required file from system property " + propertyName + " does not exist: " + file.getAbsolutePath)
    }
    file.getAbsoluteFile
  }

  private def requiredJavaHome(): String = {
    val javaHome = firstNotBlank(
      System.getProperty(JavaHomeProperty),
      System.getenv("IT_JAVA_HOME"),
      System.getenv("JAVA_8_HOME")
    )
    if (javaHome == null) {
      throw new IllegalStateException("Integration tests require Java 8 for nested sbt processes. Set IT_JAVA_HOME or JAVA_8_HOME.")
    }

    val javaBin = new File(javaHome, "bin" + File.separator + "java")
    if (!javaBin.isFile) {
      throw new IllegalStateException("Configured Java home does not contain bin/java: " + new File(javaHome).getAbsolutePath)
    }
    new File(javaHome).getAbsolutePath
  }

  private def firstNotBlank(values: String*): String = {
    values.find(value => value != null && value.trim.nonEmpty).orNull
  }

  private def addIfNotBlank(list: ListBuffer[String], value: String): Unit = {
    if (value != null && value.trim.nonEmpty) {
      list += value
    }
  }

  private def isSbt100Test(workingDir: String): Boolean = {
    val normalized = new File(workingDir).getAbsolutePath.replace(File.separatorChar, '/')
    normalized.contains("/test/testdata/1.0/")
  }
}
