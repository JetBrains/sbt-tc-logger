package org.jetbrains.teamcity.plugins.sbt.logger.utils

import jetbrains.buildServer.messages.serviceMessages.{ServiceMessage, ServiceMessageParserCallback, ServiceMessagesParser}
import org.jetbrains.sbt.integrationTests.{FileUtils, SbtIntegrationTestLayout}
import org.junit.Assert

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.ParseException
import java.util.regex.{Matcher, Pattern}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Values which typed golden placeholders may refer to. */
private[logger] final case class TranscriptContext(
  repoRoot: File,
  workDir: File,
  sbtGlobalBase: File,
  sbtBootDirectory: File,
  sbtCoursierHome: File,
  sbtIvyHome: File,
  javaHome: File,
  loggerVersion: String
) {
  val paths: Map[String, String] = Map(
    "repo-root" -> normalise(repoRoot),
    "work-dir" -> normalise(workDir),
    "sbt-global-base" -> normalise(sbtGlobalBase),
    "sbt-boot-directory" -> normalise(sbtBootDirectory),
    "sbt-coursier-home" -> normalise(sbtCoursierHome),
    "sbt-ivy-home" -> normalise(sbtIvyHome),
    "java-home" -> normalise(javaHome),
    "user-home" -> FileUtils.normalisePathSeparator(System.getProperty("user.home"))
  )

  private def normalise(file: File): String = FileUtils.normalisedAbsolutePath(file)
}

private[logger] final case class BoundedTranscript(lines: Vector[String], loggerVersion: String)

/** Locates and validates the logger-status handshake which bounds the product transcript. */
private[logger] object SbtTranscriptBoundary {
  final case class ExpectedHandshake(
    teamCityVersion: Option[String],
    preserveConsole: Boolean,
    useTeamCityTestResultLogger: Boolean,
    showTestTaskOutput: Boolean,
    detailedDependencyResolution: Boolean,
    renderObjectEventDetails: Boolean
  )

  private val Start = "TeamCity sbt logger"
  private val VersionPrefix = "  Version: "

  def extract(output: String, expected: ExpectedHandshake): BoundedTranscript = {
    val lines = output.linesIterator.toVector
    val startIndexes = lines.zipWithIndex.collect { case (Start, index) => index }
    if (startIndexes.isEmpty) fail("Missing logger-status handshake.")

    val start = startIndexes.head
    val preBoundaryServiceMessage = lines.take(start).zipWithIndex.collectFirst {
      case (line, index) if line.contains("##teamcity[") => index -> line
    }
    preBoundaryServiceMessage.foreach { case (index, line) =>
      fail(
        s"TeamCity service message before the transcript boundary at output line ${index + 1}: " +
          TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput(line)
      )
    }

    val expectedTail = Vector(
      expected.teamCityVersion.fold("  TeamCity: not detected")(version => s"  TeamCity: $version"),
      if (expected.teamCityVersion.isDefined) "  Status: active" else "  Status: inactive",
      s"  Preserve SBT console: ${booleanSetting(expected.preserveConsole)}",
      s"  Use TeamCity test result logger: ${booleanSetting(expected.useTeamCityTestResultLogger, defaultValue = true, overridden = expected.preserveConsole)}",
      s"  Show test-task output: ${booleanSetting(expected.showTestTaskOutput, defaultValue = true, overridden = expected.preserveConsole)}",
      s"  Detailed dependency resolution: ${booleanSetting(expected.detailedDependencyResolution)}"
    ) ++ Option.when(expected.renderObjectEventDetails)(
      s"  Render ObjectEvent details: ${booleanSetting(expected.renderObjectEventDetails)}"
    )
    val handshakeSize = 2 + expectedTail.size
    val handshake = lines.slice(start, start + handshakeSize)
    if (handshake.size != handshakeSize) fail(s"Incomplete logger-status handshake at output line ${start + 1}.")
    if (handshake.head != Start) fail(s"Malformed logger-status handshake start at output line ${start + 1}.")
    if (!handshake(1).startsWith(VersionPrefix) || handshake(1).stripPrefix(VersionPrefix).trim.isEmpty) {
      fail(s"Malformed logger version in handshake: '${handshake(1)}'.")
    }
    val actualTail = handshake.drop(2)
    if (actualTail != expectedTail) {
      fail(
        s"Malformed logger-status handshake.\nExpected:\n${expectedTail.mkString("\n")}\nActual:\n" +
          actualTail.map(TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput).mkString("\n")
      )
    }

    BoundedTranscript(lines.drop(start + handshakeSize), handshake(1).stripPrefix(VersionPrefix))
  }

  private def booleanSetting(value: Boolean, defaultValue: Boolean = false, overridden: Boolean = false): String = {
    val annotations =
      (if (value == defaultValue) Seq("default") else Nil) ++
        (if (overridden) Seq("overridden by preserveConsole") else Nil)
    s"$value${if (annotations.nonEmpty) s" (${annotations.mkString("; ")})" else ""}"
  }

  private def fail(message: String): Nothing =
    throw new AssertionError(TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput(message))
}

/** Exact, line-bounded transcript verifier and candidate renderer. */
private[logger] object SbtOutputVerifier {
  val CandidateModeProperty = "sbt.logger.transcripts.candidate"

  def goldenFile(fixtureDirectory: File, outputProfile: String, scenarioId: String): File =
    new File(fixtureDirectory, s"expected/$outputProfile/$scenarioId.txt")

  def candidateFile(repoRoot: File, outputProfile: String, scenarioId: String): File =
    new File(repoRoot, s"target/integration-tests/output-candidates/$outputProfile/$scenarioId.txt")

  def verify(lines: Vector[String], golden: File, context: TranscriptContext): Unit = {
    validateTeamCityLines(lines)
    if (!golden.isFile) {
      throw new AssertionError(s"Missing exact transcript golden: ${FileUtils.normalisedAbsolutePath(golden)}")
    }
    val goldenLines = FileUtils.readLines(golden).toVector
    val document = GoldenParser.parse(goldenLines, golden)
    ExactMatcher.verify(document, lines, context, golden)
  }

  def writeCandidate(lines: Vector[String], destination: File, context: TranscriptContext): Unit = {
    validateTeamCityLines(lines)
    val rendered = CandidateRenderer.render(lines, context)
    val parent = destination.toPath.getParent
    Files.createDirectories(parent)
    Files.writeString(
      destination.toPath,
      rendered.mkString("", System.lineSeparator(), System.lineSeparator()),
      StandardCharsets.UTF_8
    )
    // Candidate output must itself be accepted by the same parser and matcher before it is offered for review.
    ExactMatcher.verify(GoldenParser.parse(rendered, destination), lines, context, destination)
  }

  def validateTeamCityLines(lines: Seq[String]): Unit = lines.zipWithIndex.foreach { case (line, index) =>
    if (line.contains("##teamcity[")) validateTeamCityLine(line, index + 1)
  }

  private def validateTeamCityLine(line: String, lineNumber: Int): Unit = {
    if (!line.startsWith("##teamcity[") || !line.endsWith("]")) {
      fail(
        s"Malformed TeamCity-looking output line $lineNumber: " +
          TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput(line)
      )
    }

    val messages = mutable.ArrayBuffer.empty[ServiceMessage]
    val unparsedText = mutable.ArrayBuffer.empty[String]
    val errors = mutable.ArrayBuffer.empty[(ParseException, String)]
    val parser = new ServiceMessagesParser
    parser.setValidateRequiredAttributes(true)
    parser.parse(line, new ServiceMessageParserCallback {
      override def regularText(text: String): Unit = if (text.nonEmpty) unparsedText += text
      override def serviceMessage(message: ServiceMessage): Unit = messages += message
      override def parseException(error: ParseException, text: String): Unit = errors += error -> text
    })
    if (errors.nonEmpty || unparsedText.nonEmpty || messages.size != 1) {
      val details = errors.headOption.map(_._1.getMessage).getOrElse {
        if (unparsedText.nonEmpty) s"unparsed text: ${unparsedText.mkString}" else s"parsed ${messages.size} messages"
      }
      fail(
        s"Malformed TeamCity service message at output line $lineNumber ($details): " +
          TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput(line)
      )
    }
  }

  private def fail(message: String): Nothing = throw new AssertionError(message)

  private sealed trait GoldenDocument
  private case object ExpectEmpty extends GoldenDocument
  private final case class Transcript(segments: Vector[Segment]) extends GoldenDocument

  private sealed trait Segment
  private sealed trait Atom extends Segment {
    def sourceLine: Int
    def description: String
    def tryMatch(actual: String, state: Bindings, context: TranscriptContext): Option[Bindings]
  }
  private final case class Literal(template: LineTemplate, sourceLine: Int) extends Atom {
    override def description: String = template.source
    override def tryMatch(actual: String, state: Bindings, context: TranscriptContext): Option[Bindings] =
      template.tryMatch(actual, state, context)
  }
  private final case class Noise(name: String, sourceLine: Int) extends Atom {
    override def description: String = s"[[noise:$name]]"
    override def tryMatch(actual: String, state: Bindings, context: TranscriptContext): Option[Bindings] =
      Option.when(NoiseRecognizers.matches(name, actual))(state)
  }
  private final case class Unordered(lanes: Vector[Lane]) extends Segment
  private final case class Lane(name: String, atoms: Vector[Atom], sourceLine: Int)

  private object GoldenParser {
    private val NoiseDirective = "\\[\\[noise:([a-z][a-z0-9-]*)\\]\\]".r
    private val LaneStart = "\\[\\[lane:([a-z][a-z0-9-]*)\\]\\]".r

    def parse(lines: Vector[String], source: File): GoldenDocument = {
      if (lines.isEmpty) invalid(source, 1, "Empty goldens are forbidden; use [[expect-empty]].")
      if (lines == Vector("[[expect-empty]]")) return ExpectEmpty
      if (lines.contains("[[expect-empty]]")) invalid(source, lines.indexOf("[[expect-empty]]") + 1, "[[expect-empty]] must be the only line.")

      val segments = Vector.newBuilder[Segment]
      var index = 0
      while (index < lines.size) {
        lines(index) match {
          case "[[unordered]]" =>
            val (unordered, next) = parseUnordered(lines, index, source)
            segments += unordered
            index = next
          case line =>
            segments += parseAtom(line, index + 1, source)
            index += 1
        }
      }
      val result = segments.result()
      if (result.isEmpty) invalid(source, 1, "A transcript must contain at least one expectation.")
      Transcript(result)
    }

    private def parseUnordered(lines: Vector[String], start: Int, source: File): (Unordered, Int) = {
      val lanes = Vector.newBuilder[Lane]
      val names = mutable.HashSet.empty[String]
      var index = start + 1
      while (index < lines.size && lines(index) != "[[/unordered]]") {
        val (name, laneLine) = lines(index) match {
          case LaneStart(value) => value -> (index + 1)
          case other => invalid(source, index + 1, s"Expected [[lane:name]] inside unordered block, found: $other")
        }
        if (!names.add(name)) invalid(source, index + 1, s"Duplicate unordered lane '$name'.")
        index += 1
        val atoms = Vector.newBuilder[Atom]
        while (index < lines.size && lines(index) != "[[/lane]]") {
          if (lines(index) == "[[unordered]]" || lines(index).startsWith("[[lane:") || lines(index) == "[[/unordered]]") {
            invalid(source, index + 1, "Nested or unterminated unordered lane.")
          }
          atoms += parseAtom(lines(index), index + 1, source)
          index += 1
        }
        if (index >= lines.size) invalid(source, laneLine, s"Missing [[/lane]] for lane '$name'.")
        val laneAtoms = atoms.result()
        if (laneAtoms.isEmpty) invalid(source, laneLine, s"Unordered lane '$name' must not be empty.")
        lanes += Lane(name, laneAtoms, laneLine)
        index += 1
      }
      if (index >= lines.size) invalid(source, start + 1, "Missing [[/unordered]].")
      val result = lanes.result()
      if (result.size < 2) invalid(source, start + 1, "An unordered block needs at least two ordered lanes.")
      Unordered(result) -> (index + 1)
    }

    private def parseAtom(line: String, lineNumber: Int, source: File): Atom = line match {
      case NoiseDirective(name) =>
        if (!NoiseRecognizers.names.contains(name)) invalid(source, lineNumber, s"Unknown noise recognizer '$name'.")
        Noise(name, lineNumber)
      case directive if directive.startsWith("[[") && directive.endsWith("]]" ) =>
        invalid(source, lineNumber, s"Unknown or misplaced directive: $directive")
      case literal => Literal(LineTemplate.compile(literal, source, lineNumber), lineNumber)
    }

    private def invalid(source: File, line: Int, message: String): Nothing =
      throw new IllegalArgumentException(s"${FileUtils.normalisedAbsolutePath(source)}:$line: $message")
  }

  private final case class BindingKey(kind: String, name: String)
  private final case class Bindings(values: Map[BindingKey, String]) {
    def bind(key: BindingKey, value: String, distinctWithinKind: Boolean): Option[Bindings] = values.get(key) match {
      case Some(existing) => Option.when(existing == value)(this)
      case None if distinctWithinKind && values.exists { case (other, existing) => other.kind == key.kind && existing == value } => None
      case None => Some(copy(values = values.updated(key, value)))
    }
  }
  private object Bindings { val empty: Bindings = Bindings(Map.empty) }

  private final case class Capture(group: Int, placeholder: Placeholder)
  private final case class LineTemplate(source: String, regex: Pattern, captures: Vector[Capture]) {
    def tryMatch(actual: String, initial: Bindings, context: TranscriptContext): Option[Bindings] = {
      val matcher = regex.matcher(actual)
      if (!matcher.matches()) return None
      captures.foldLeft(Option(initial)) { case (state, capture) =>
        state.flatMap(capture.placeholder.accept(matcher.group(capture.group), _, context))
      }
    }
  }

  private sealed trait Placeholder {
    def regex(context: TranscriptContext): String
    def captures: Boolean = true
    def accept(value: String, bindings: Bindings, context: TranscriptContext): Option[Bindings] = Some(bindings)
  }
  private final case class BoundPlaceholder(kind: String, name: String, distinct: Boolean, valueRegex: String) extends Placeholder {
    override def regex(context: TranscriptContext): String = valueRegex
    override def accept(value: String, bindings: Bindings, context: TranscriptContext): Option[Bindings] =
      bindings.bind(BindingKey(kind, name), value, distinct)
  }
  private final case class ExactPlaceholder(value: TranscriptContext => String) extends Placeholder {
    override def captures: Boolean = false
    override def regex(context: TranscriptContext): String = Pattern.quote(value(context))
  }
  private final case class ValidatedPlaceholder(
    valueRegex: String,
    validation: (String, TranscriptContext) => Boolean = (_, _) => true
  ) extends Placeholder {
    override def regex(context: TranscriptContext): String = valueRegex
    override def accept(value: String, bindings: Bindings, context: TranscriptContext): Option[Bindings] =
      Option.when(validation(value, context))(bindings)
  }

  private object LineTemplate {
    private val PlaceholderPattern = "\\{\\{([^{}]+)\\}\\}".r
    private val Named = "([a-z][a-z0-9-]*):([a-z][a-z0-9-]*)".r
    private val JavaVersionPlaceholder = "java-version:([0-9]+)".r

    def compile(source: String, file: File, lineNumber: Int): LineTemplate = {
      val regex = new StringBuilder("^")
      val captures = Vector.newBuilder[Capture]
      var cursor = 0
      var group = 0
      PlaceholderPattern.findAllMatchIn(source).foreach { token =>
        regex.append(Pattern.quote(source.substring(cursor, token.start)))
        val placeholder = parsePlaceholder(token.group(1), file, lineNumber)
        if (placeholder.captures) {
          group += 1
          regex.append('(').append(placeholder.regex(PlaceholderContext)).append(')')
          captures += Capture(group, placeholder)
        } else {
          regex.append(placeholder.regex(PlaceholderContext))
        }
        cursor = token.end
      }
      regex.append(Pattern.quote(source.substring(cursor))).append('$')
      // Exact path and logger placeholders depend on the run context, so compile their marker form lazily below.
      LineTemplate(source, Pattern.compile(rewriteContextMarkers(regex.toString)), captures.result())
    }

    // A synthetic context lets parsing reject unknown path names while leaving stable markers for per-run substitution.
    private val PlaceholderContext = TranscriptContext(
      new File("/__TRANSCRIPT_PATH_repo-root__"),
      new File("/__TRANSCRIPT_PATH_work-dir__"),
      new File("/__TRANSCRIPT_PATH_sbt-global-base__"),
      new File("/__TRANSCRIPT_PATH_sbt-boot-directory__"),
      new File("/__TRANSCRIPT_PATH_sbt-coursier-home__"),
      new File("/__TRANSCRIPT_PATH_sbt-ivy-home__"),
      new File("/__TRANSCRIPT_PATH_java-home__"),
      "__TRANSCRIPT_LOGGER_VERSION__"
    )

    private def rewriteContextMarkers(regex: String): String = regex

    private def parsePlaceholder(text: String, file: File, lineNumber: Int): Placeholder = text match {
      case "logger-version" => ExactPlaceholder(_.loggerVersion)
      case "dependency-outcome" =>
        ValidatedPlaceholder("(?:local cache hit|downloaded)")
      case Named("flow", name) => BoundPlaceholder("flow", name, distinct = true, "[^'\\s\\]]+")
      case Named("build-id", name) => BoundPlaceholder("build-id", name, distinct = true, "-?[0-9]+")
      case Named("duration", _) => ValidatedPlaceholder("[0-9]+(?:\\.[0-9]+)?")
      case Named("timestamp", _) => ValidatedPlaceholder("(?:[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.+-]+|[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3})")
      case Named("thread", _) => ValidatedPlaceholder("pool-[0-9]+-thread-[0-9]+")
      case Named("hash", _) => ValidatedPlaceholder("[0-9a-fA-F]{6,16}")
      case JavaVersionPlaceholder(major) =>
        JavaVersion.regex(major).fold {
          invalid(file, lineNumber, s"Unsupported Java major version '$major'.")
        }(ValidatedPlaceholder(_))
      case "dependency-metadata" => ValidatedPlaceholder("(?: \\([^)]*?, [0-9]+(?:\\.[0-9]+)? ?(?:ms|s)\\))?")
      case Named("framework-stack-tail", framework) =>
        ValidatedPlaceholder("(?:(?:\\|.)|[^'])*+", (tail, _) => FrameworkStackTail.isRecognized(framework, tail))
      case Named("input-file-mappings", fixture) =>
        ValidatedPlaceholder("(?:(?:\\|.)|[^'])*+", (value, _) => InputFileMappings.isRecognized(fixture, value))
      case Named("path", name) =>
        if (!PlaceholderContext.paths.contains(name)) invalid(file, lineNumber, s"Unknown path root '$name'.")
        // Marker substitution is handled by contextualCompile before matching.
        ExactPlaceholder(context => context.paths(name))
      case other => invalid(file, lineNumber, s"Unknown typed placeholder {{$other}}.")
    }

    private def invalid(file: File, line: Int, message: String): Nothing =
      throw new IllegalArgumentException(s"${FileUtils.normalisedAbsolutePath(file)}:$line: $message")
  }

  /** Recompiles a literal with context-dependent exact placeholders before matching. */
  private def contextualTemplate(template: LineTemplate, context: TranscriptContext): LineTemplate = {
    val PlaceholderPattern = "\\{\\{([^{}]+)\\}\\}".r
    val regex = new StringBuilder("^")
    val captures = Vector.newBuilder[Capture]
    var cursor = 0
    var group = 0
    PlaceholderPattern.findAllMatchIn(template.source).foreach { token =>
      regex.append(Pattern.quote(template.source.substring(cursor, token.start)))
      val text = token.group(1)
      val placeholder: Placeholder = text match {
        case "logger-version" => ExactPlaceholder(_.loggerVersion)
        case "dependency-outcome" => ValidatedPlaceholder("(?:local cache hit|downloaded)")
        case value if value.startsWith("path:") => ExactPlaceholder(ctx => ctx.paths(value.stripPrefix("path:")))
        case value if value.startsWith("flow:") => BoundPlaceholder("flow", value.stripPrefix("flow:"), distinct = true, "[^'\\s\\]]+")
        case value if value.startsWith("build-id:") => BoundPlaceholder("build-id", value.stripPrefix("build-id:"), distinct = true, "-?[0-9]+")
        case value if value.startsWith("duration:") => ValidatedPlaceholder("[0-9]+(?:\\.[0-9]+)?")
        case value if value.startsWith("timestamp:") => ValidatedPlaceholder("(?:[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.+-]+|[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3})")
        case value if value.startsWith("thread:") => ValidatedPlaceholder("pool-[0-9]+-thread-[0-9]+")
        case value if value.startsWith("hash:") => ValidatedPlaceholder("[0-9a-fA-F]{6,16}")
        case value if value.startsWith("java-version:") =>
          JavaVersion.regex(value.stripPrefix("java-version:")).fold {
            throw new IllegalArgumentException(s"Unsupported Java version placeholder {{$value}}.")
          }(ValidatedPlaceholder(_))
        case "dependency-metadata" => ValidatedPlaceholder("(?: \\([^)]*?, [0-9]+(?:\\.[0-9]+)? ?(?:ms|s)\\))?")
        case value if value.startsWith("framework-stack-tail:") =>
          val framework = value.stripPrefix("framework-stack-tail:")
          ValidatedPlaceholder("(?:(?:\\|.)|[^'])*+", (tail, _) => FrameworkStackTail.isRecognized(framework, tail))
        case value if value.startsWith("input-file-mappings:") =>
          val fixture = value.stripPrefix("input-file-mappings:")
          ValidatedPlaceholder("(?:(?:\\|.)|[^'])*+", (text, _) => InputFileMappings.isRecognized(fixture, text))
      }
      if (placeholder.captures) {
        group += 1
        regex.append('(').append(placeholder.regex(context)).append(')')
        captures += Capture(group, placeholder)
      } else regex.append(placeholder.regex(context))
      cursor = token.end
    }
    regex.append(Pattern.quote(template.source.substring(cursor))).append('$')
    LineTemplate(template.source, Pattern.compile(regex.toString), captures.result())
  }

  private object FrameworkStackTail {
    private val AllowedPrefixes = Set(
      "org.scalatest.", "org.specs2.", "org.junit.", "junit.", "sbt.", "sbt.internal.", "xsbti.",
      "com.novocode.junit.", "scala.", "java.", "java.base/", "java.util.concurrent.", "jdk.internal.", "sun.reflect."
    )

    def isRecognized(framework: String, value: String): Boolean = {
      if (!Set("scalatest", "specs2", "junit").contains(framework)) return false
      value.split("\\|n", -1).filter(_.nonEmpty).forall { frame =>
        val trimmed = frame.stripPrefix("\t").trim
        trimmed.startsWith("...") ||
          (trimmed.startsWith("at ") && AllowedPrefixes.exists(prefix => trimmed.stripPrefix("at ").startsWith(prefix))) ||
          trimmed.startsWith("Caused by: ")
      }
    }
  }

  /**
   * Validates a package input-mapping set whose entries are emitted in file-system iteration order.
   *
   * The placeholder is deliberately limited to the Java-sources fixture: it requires every directory mapping,
   * every generated file mapping, their common classes directory, and the exact one-to-one source/destination
   * relation. Only the order of independent generated-file entries is relaxed.
   */
  private object InputFileMappings {
    private val TextAttribute = "text='((?:(?:\\|.)|[^'])*+)'".r
    private val DebugPrefix = "|[debug|] "
    private val EntryPrefix = DebugPrefix + "\t"
    private val MappingPrefix = DebugPrefix + "\t  "
    private val Directories = Vector("com", "com/jetbrains", "com/jetbrains/sbt", "com/jetbrains/sbt/test")
    private val JavaSourceClasses = Set(
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloScala$.class",
      "com/jetbrains/sbt/test/HelloWorld.class"
    )
    private val Scala3JavaSourceEntries = Set(
      "com/jetbrains/sbt/test/HelloScala.class",
      "com/jetbrains/sbt/test/HelloScala$.class",
      "com/jetbrains/sbt/test/HelloWorld.class",
      "com/jetbrains/sbt/test/HelloScala.tasty"
    )

    def isRecognized(fixture: String, value: String): Boolean = fixture match {
      case "java-sources" => isJavaSourcesMapping(value)
      case _ => false
    }

    def tokenize(line: String): String =
      TextAttribute.replaceAllIn(line, matched => {
        val replacement =
          if (isRecognized("java-sources", matched.group(1))) "text='{{input-file-mappings:java-sources}}'"
          else matched.matched
        Matcher.quoteReplacement(replacement)
      })

    private def isJavaSourcesMapping(value: String): Boolean = {
      val lines = value.split("\\|n", -1).toVector
      if (lines.headOption != Some(DebugPrefix + "Input file mappings:")) return false

      val pairs = lines.drop(1).grouped(2).collect { case Vector(entry, path) => entry -> path }.toVector
      if (lines.size != 1 + pairs.size * 2) return false
      matchesSbt1Mapping(pairs) || matchesSbt2Mapping(pairs)
    }

    private def matchesSbt1Mapping(pairs: Vector[(String, String)]): Boolean = {
      if (pairs.size != Directories.size + JavaSourceClasses.size) return false
      val directoryPairs = pairs.take(Directories.size)
      val classPairs = pairs.drop(Directories.size)
      commonClassesRoot(pairs, Directories.toSet ++ JavaSourceClasses, "/target/scala-2.13/classes/").exists { classesRoot =>
        directoryPairs.zip(Directories).forall { case ((entry, path), directory) =>
          entry == EntryPrefix + directory && path == MappingPrefix + classesRoot + directory
        } && matchesUnorderedMappings(classPairs, JavaSourceClasses, classesRoot)
      }
    }

    private def matchesSbt2Mapping(pairs: Vector[(String, String)]): Boolean =
      commonClassesRoot(
        pairs,
        Scala3JavaSourceEntries,
        "/target/out/jvm/scala-3.8.4/java-sources-compile-run/classes/"
      ).exists { classesRoot =>
        matchesUnorderedMappings(pairs, Scala3JavaSourceEntries, classesRoot)
      }

    private def commonClassesRoot(
      pairs: Vector[(String, String)],
      expectedEntries: Set[String],
      expectedRootSuffix: String
    ): Option[String] = {
      val entryNames = pairs.map(_._1.stripPrefix(EntryPrefix))
      val entriesMatch = pairs.size == expectedEntries.size && entryNames.size == expectedEntries.size &&
        entryNames.toSet == expectedEntries && pairs.forall(_._1.startsWith(EntryPrefix))
      val roots = pairs.map { case (entry, path) =>
        val name = entry.stripPrefix(EntryPrefix)
        val outputPath = path.stripPrefix(MappingPrefix)
        Option.when(path.startsWith(MappingPrefix) && outputPath.endsWith(name))(outputPath.stripSuffix(name))
      }
      if (!entriesMatch || !roots.forall(_.isDefined)) None
      else {
        roots.flatten.distinct match {
          case Vector(root) if root.endsWith(expectedRootSuffix) => Some(root)
          case _ => None
        }
      }
    }

    private def matchesUnorderedMappings(
      pairs: Vector[(String, String)],
      expectedEntries: Set[String],
      classesRoot: String
    ): Boolean =
      pairs.size == expectedEntries.size &&
        pairs.map(_._1.stripPrefix(EntryPrefix)).toSet == expectedEntries &&
        pairs.forall { case (entry, path) =>
          entry.startsWith(EntryPrefix) && path == MappingPrefix + classesRoot + entry.stripPrefix(EntryPrefix)
        }
  }

  /** Recognizes the Java-version values emitted by the fixture without coupling transcripts to an agent patch level. */
  private object JavaVersion {
    private val Versions = Vector(
      "8" -> "1\\.8\\.0_[0-9]+(?:-b[0-9]+)?",
      "17" -> "17\\.0\\.[0-9]+"
    )

    def regex(major: String): Option[String] = Versions.collectFirst { case (`major`, pattern) => pattern }

    def tokenize(line: String): String =
      Versions.collectFirst {
        case (major, pattern) if line.matches(pattern) => s"{{java-version:$major}}"
      }.getOrElse(line)
  }

  private object ExactMatcher {
    def verify(document: GoldenDocument, actual: Vector[String], context: TranscriptContext, source: File): Unit = document match {
      case ExpectEmpty =>
        Assert.assertTrue(
          s"Expected an empty bounded transcript from ${FileUtils.normalisedAbsolutePath(source)}, but got:\n${numbered(actual)}",
          actual.isEmpty
        )
      case Transcript(segments) =>
        val contextualSegments = segments.map {
          case literal: Literal => literal.copy(template = contextualTemplate(literal.template, context))
          case unordered: Unordered => unordered.copy(lanes = unordered.lanes.map(lane => lane.copy(atoms = lane.atoms.map {
            case literal: Literal => literal.copy(template = contextualTemplate(literal.template, context))
            case atom => atom
          })))
          case other => other
        }
        var states = Vector(0 -> Bindings.empty)
        contextualSegments.foreach { segment =>
          val next = states.flatMap { case (index, bindings) => matchSegment(segment, actual, index, bindings, context) }
          if (next.isEmpty) mismatch(source, segment, actual, states.map(_._1).maxOption.getOrElse(0))
          states = deduplicate(next)
        }
        if (!states.exists(_._1 == actual.size)) {
          val furthest = states.map(_._1).max
          fail(
            s"Exact transcript has unexpected trailing output after line $furthest in ${FileUtils.normalisedAbsolutePath(source)}:\n" +
              numbered(actual.drop(furthest), furthest + 1)
          )
        }
    }

    private def matchSegment(
      segment: Segment,
      actual: Vector[String],
      index: Int,
      bindings: Bindings,
      context: TranscriptContext
    ): Vector[(Int, Bindings)] = segment match {
      case atom: Atom => matchAtom(atom, actual, index, bindings, context)
      case Unordered(lanes) => matchUnordered(lanes, actual, index, bindings, context)
    }

    private def matchAtom(
      atom: Atom,
      actual: Vector[String],
      index: Int,
      bindings: Bindings,
      context: TranscriptContext
    ): Vector[(Int, Bindings)] = atom match {
      case Noise("sbt-compiler-bridge", _) =>
        val consumed = NoiseRecognizers.compilerBridgeLength(actual, index)
        if (consumed == 2) Vector(index + consumed -> bindings) else Vector(index -> bindings)
      case _ if index < actual.size => atom.tryMatch(actual(index), bindings, context).toVector.map(index + 1 -> _)
      case _ => Vector.empty
    }

    private def matchUnordered(
      lanes: Vector[Lane],
      actual: Vector[String],
      start: Int,
      initial: Bindings,
      context: TranscriptContext
    ): Vector[(Int, Bindings)] = {
      val memo = mutable.Map.empty[(Int, Vector[Int], Bindings), Vector[(Int, Bindings)]]
      def loop(index: Int, positions: Vector[Int], bindings: Bindings): Vector[(Int, Bindings)] = {
        memo.getOrElseUpdate((index, positions, bindings), {
          if (positions.indices.forall(lane => positions(lane) == lanes(lane).atoms.size)) Vector(index -> bindings)
          else {
            lanes.indices.foldLeft(Vector.empty[(Int, Bindings)]) { (results, laneIndex) =>
              if (results.size >= 256) results
              else {
                val position = positions(laneIndex)
                val expanded =
                  if (position >= lanes(laneIndex).atoms.size) Vector.empty
                  else matchAtom(lanes(laneIndex).atoms(position), actual, index, bindings, context).flatMap {
                    case (nextIndex, nextBindings) =>
                      loop(nextIndex, positions.updated(laneIndex, position + 1), nextBindings)
                  }
                deduplicate(results ++ expanded)
              }
            }
          }
        })
      }
      loop(start, Vector.fill(lanes.size)(0), initial)
    }

    private def deduplicate(states: Vector[(Int, Bindings)]): Vector[(Int, Bindings)] = states.distinct.take(256)

    private def mismatch(source: File, segment: Segment, actual: Vector[String], index: Int): Nothing = {
      val expectation = segment match {
        case atom: Atom => s"golden line ${atom.sourceLine}: ${atom.description}"
        case Unordered(lanes) => s"unordered lanes ${lanes.map(_.name).mkString(", ")}"
      }
      val actualLine = actual.lift(index).fold("<end of transcript>")(diagnosticLine)
      fail(
        s"Exact transcript mismatch in ${FileUtils.normalisedAbsolutePath(source)} at output line ${index + 1}.\n" +
          s"Expected ${diagnosticLine(expectation)}\nActual: $actualLine\nContext:\n${numbered(actual.slice((index - 2).max(0), index + 3), (index - 2).max(0) + 1)}"
      )
    }

    private def numbered(lines: Seq[String], start: Int = 1): String =
      lines.zipWithIndex.map { case (line, index) => f"${start + index}%5d | ${diagnosticLine(line)}" }.mkString("\n")

    private def diagnosticLine(line: String): String =
      TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput(line)

    private def fail(message: String): Nothing =
      throw new AssertionError(TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput(message))
  }

  private object NoiseRecognizers {
    val names: Set[String] = Set(
      "sbt-task-summary",
      "sbt-debug-line",
      "zinc-debug-message",
      "framework-stack-tail",
      "dependency-resource-outcome",
      "sbt-compiler-bridge"
    )

    private val SbtTaskSummary =
      "^\\[(?:success|error)\\] (?:elapsed time: [0-9]+(?:\\.[0-9]+)? s, cache [0-9]+%, .+|Total time: [0-9]+(?:\\.[0-9]+)? s(?:, completed .+)?)$".r
    private val RawFrameworkFrame = "^\\s*at (?:org\\.(?:scalatest|specs2|junit)\\.|junit\\.|sbt\\.|scala\\.|java\\.|java\\.base/).+$".r
    private val KnownZincPrefixes = Vector(
      "[debug] [zinc] ",
      "[debug] IncrementalCompile",
      "[debug] previous = ",
      "[debug] current source = ",
      "[debug] > initialChanges = ",
      "[debug] Full compilation",
      "[debug] No changes",
      "[debug] Created transactional ClassFileManager",
      "[debug] Removing the temporary directory",
      "[debug] We backup class files",
      "[debug] About to delete class files",
      "[debug] Rolling back changes",
      "[debug] all ",
      "[debug] Recompiling ",
      "[debug] Compilation failed",
      "[debug] wrote ",
      "[debug] not up to date.",
      "[debug] Updating ",
      "[debug] Done updating "
    )

    def matches(name: String, line: String): Boolean = name match {
      case "sbt-task-summary" => SbtTaskSummary.matches(line)
      case "sbt-debug-line" => isKnownRawSbtDebug(line)
      case "zinc-debug-message" => serviceMessage(line).exists { message =>
        message.getMessageName == "message" &&
          Option(message.getAttributes.get("status")).contains("NORMAL") &&
          Option(message.getAttributes.get("text")).exists(text => KnownZincPrefixes.exists(text.startsWith))
      }
      case "framework-stack-tail" => RawFrameworkFrame.matches(line)
      case "dependency-resource-outcome" => serviceMessage(line).exists { message =>
        val attributes = message.getAttributes.asScala
        message.getMessageName == "message" &&
          attributes.get("flowId").contains("teamcity-sbt-dependency-resolution") &&
          attributes.get("text").exists(text =>
            text.matches("^\\[[^]]+\\] (?:local cache hit|downloaded(?: in [0-9.]+ ?(?:ms|s))?|failed download attempt(?: in [0-9.]+ ?(?:ms|s))?) https?://[^ ]+$")
          )
      }
      case "sbt-compiler-bridge" => false // This strict recognizer is a two-line optional block, handled by the matcher.
    }

    def compilerBridgeLength(lines: Vector[String], index: Int): Int = {
      val pair = lines.slice(index, index + 2)
      if (pair.size != 2) return 0
      (bridgeLine(pair.head), bridgeCompletion(pair(1))) match {
        case (Some((flow1, _)), Some(flow2)) if flow1 == flow2 => 2
        case _ => 0
      }
    }

    private def bridgeLine(line: String): Option[(Option[String], String)] = outputText(line).flatMap { case (flow, text) =>
      "^\\[info\\] Non-compiled module 'compiler-bridge_[^']+' for Scala ([0-9.]+)\\. Compiling\\.\\.\\.$".r
        .findFirstMatchIn(text).map(result => flow -> result.group(1))
    }

    private def bridgeCompletion(line: String): Option[Option[String]] = outputText(line).flatMap { case (flow, text) =>
      "^\\[info\\]   Compilation completed in [0-9]+(?:\\.[0-9]+)?s\\.$".r
        .findFirstMatchIn(text).map(_ => flow)
    }

    private def outputText(line: String): Option[(Option[String], String)] = {
      if (line.startsWith("[info] ")) Some(None -> line)
      else serviceMessage(line).flatMap { message =>
        val attributes = message.getAttributes.asScala
        Option.when(message.getMessageName == "message" && attributes.get("status").contains("NORMAL")) {
          attributes.get("flowId") -> attributes("text")
        }
      }
    }

    private def isKnownRawSbtDebug(line: String): Boolean = {
      val prefixes = Vector(
        "[debug] > Exec(", "[debug] Evaluating tasks:", "[debug] Running task...", "[debug] not up to date.",
        "[debug] Updating ", "[debug] Done updating ", "[debug] IncrementalCompile", "[debug] previous = ",
        "[debug] current source = ", "[debug] > initialChanges = ", "[debug] Full compilation", "[debug] No changes",
        "[debug] Created transactional ClassFileManager", "[debug] Removing the temporary directory", "[debug] wrote ",
        "[debug] Packaging ", "[debug] Input file mappings:", "[debug] Done packaging."
      )
      prefixes.exists(line.startsWith) || line == "[debug] " || line == "[debug] \t"
    }

    private def serviceMessage(line: String): Option[ServiceMessage] =
      if (!line.startsWith("##teamcity[") || !line.endsWith("]")) None
      else try Some(ServiceMessage.parse(line)) catch { case _: ParseException => None }
  }

  private object CandidateRenderer {
    private val FlowAttribute = "flowId='([^']+)'".r
    private val DurationAttribute = "duration='[0-9]+(?:\\.[0-9]+)?'".r
    private val TimestampAttribute = "timestamp='[^']+'".r
    private val NumericBuildFlow = "^(-?[0-9]+)(:.+)$".r
    private val DetailsAttribute = "details='((?:(?:\\|.)|[^'])*+)'".r
    private val CompilerProject = ".*\\[([^]]+)\\]$".r

    def render(lines: Vector[String], context: TranscriptContext): Vector[String] = {
      if (lines.isEmpty) return Vector("[[expect-empty]]")
      val buildNames = semanticBuildNames(lines, context)
      val flowNames = semanticTestFlowNames(lines)
      val result = Vector.newBuilder[String]
      var index = 0
      while (index < lines.size) {
        val original = lines(index)
        if (NoiseRecognizers.matches("sbt-task-summary", original)) result += "[[noise:sbt-task-summary]]"
        else {
          result += renderLine(original, context, buildNames, flowNames)
          if (isCompilationAnnouncement(original)) {
            // This explicit strict block accepts either a cold two-line bridge compilation or a warm-cache absence.
            result += "[[noise:sbt-compiler-bridge]]"
            index += NoiseRecognizers.compilerBridgeLength(lines, index + 1)
          }
        }
        index += 1
      }
      result.result()
    }

    private def renderLine(
      original: String,
      context: TranscriptContext,
      buildNames: mutable.LinkedHashMap[String, String],
      flowNames: mutable.LinkedHashMap[String, String]
    ): String = {
      var line = original
      line = InputFileMappings.tokenize(line)
      line = JavaVersion.tokenize(line)
      context.paths.toVector.sortBy { case (_, value) => -value.length }.foreach { case (name, value) =>
        val path = Pattern.compile(Pattern.quote(value) + "(?=$|[^A-Za-z0-9._-])")
        line = path.matcher(line).replaceAll(Matcher.quoteReplacement(s"{{path:$name}}"))
      }
      line = line.replace(context.loggerVersion, "{{logger-version}}")
      line = FlowAttribute.replaceAllIn(line, matched => {
        val value = matched.group(1)
        val rendered = value match {
          case "teamcity-sbt-dependency-resolution" => value
          case NumericBuildFlow(buildId, suffix) =>
            val fallback = if (buildNames.isEmpty) "root" else s"build-${buildNames.size + 1}"
            s"{{build-id:${buildNames.getOrElseUpdate(buildId, fallback)}}}$suffix"
          case other =>
            val fallback = if (flowNames.isEmpty) "test" else s"test-${flowNames.size + 1}"
            s"{{flow:${flowNames.getOrElseUpdate(other, fallback)}}}"
        }
        s"flowId='$rendered'"
      })
      line = DurationAttribute.replaceAllIn(line, "duration='{{duration:test}}'")
      line = TimestampAttribute.replaceAllIn(line, "timestamp='{{timestamp:event}}'")
      line = line.replaceAll("(?i)(finished in )[0-9]+(?:\\.[0-9]+)?(?= ms)", "$1{{duration:task}}")
      line = line.replaceAll("(took )[0-9]+(?:\\.[0-9]+)?(?= sec)", "$1{{duration:task}}")
      line = line.replaceAll("((?:Scala|Java) (?:compilation|analysis)(?: \\+ analysis)? took )[0-9]+(?:\\.[0-9]+)?(?= s)", "$1{{duration:compile}}")
      line = line.replaceAll("(after )[0-9]+(?:\\.[0-9]+)?(?= ms)", "$1{{duration:dependency}}")
      line = line.replaceAll("(?<![0-9])[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}", "{{timestamp:log}}")
      line = line.replaceAll("pool-[0-9]+-thread-[0-9]+", "{{thread:test}}")
      line = line.replaceAll("(Running cached compiler )[0-9a-fA-F]{6,16}", "$1{{hash:compiler}}")
      line = line.replaceAll("(JavacTool@)[0-9a-fA-F]{6,16}", "$1{{hash:javac}}")
      line = line.replaceAll("sbt_([0-9a-fA-F]{6,16})", "sbt_{{hash:bg-job}}")
      line = line.replaceAll(
        "(target/bg-jobs/sbt_(?:\\{\\{hash:bg-job\\}\\}|[0-9a-fA-F]{6,16})/(?:job-[0-9]+/)?target/)[0-9a-fA-F]{6,16}(?=/)",
        "$1{{hash:bg-job-target}}"
      )
      line = line.replaceAll("local cache hit (https?://[^ '\\]]+)", "{{dependency-outcome}} $1{{dependency-metadata}}")
      line = line.replaceAll("downloaded (https?://[^ ']+) \\([^']+\\)", "{{dependency-outcome}} $1{{dependency-metadata}}")
      tokenizeFrameworkTail(line)
    }

    private def semanticBuildNames(lines: Vector[String], context: TranscriptContext): mutable.LinkedHashMap[String, String] = {
      val names = mutable.LinkedHashMap.empty[String, String]
      val used = mutable.HashSet.empty[String]
      lines.foreach { line =>
        serviceMessage(line).filter(_.getMessageName == "compilationStarted").foreach { message =>
          val attributes = message.getAttributes.asScala
          for {
            case NumericBuildFlow(buildId, _) <- attributes.get("flowId")
            compiler <- attributes.get("compiler")
            case CompilerProject(project) <- Some(compiler)
            if !names.contains(buildId)
          } {
            val base = if (project == context.workDir.getName || project == "root") "root" else kebab(project)
            val unique = uniqueName(base, used)
            names.getOrElseUpdate(buildId, unique)
          }
        }
      }
      names
    }

    private def semanticTestFlowNames(lines: Vector[String]): mutable.LinkedHashMap[String, String] = {
      val messages = lines.flatMap(serviceMessage)
      val allFlows = messages.flatMap(message => Option(message.getFlowId))
        .filterNot(_.contains(':')).filterNot(_ == "teamcity-sbt-dependency-resolution").distinct
      val names = mutable.LinkedHashMap.empty[String, String]
      if (allFlows.size == 1) names += allFlows.head -> "test"
      else {
        val used = mutable.HashSet.empty[String]
        messages.filter(_.getMessageName == "testSuiteStarted").foreach { message =>
          val attributes = message.getAttributes.asScala
          for (flow <- attributes.get("flowId"); suite <- attributes.get("name") if !names.contains(flow)) {
            val simple = suite.split('.').lastOption.getOrElse(suite).stripSuffix("Suite").stripSuffix("Test")
            val prefix = if (suite.startsWith("suites.")) "suite-" else if (suite.startsWith("tests.")) "direct-" else "suite-"
            names += flow -> uniqueName(prefix + kebab(simple), used)
          }
        }
        allFlows.filterNot(names.contains).foreach { flow =>
          names += flow -> uniqueName(if (names.isEmpty) "test" else s"test-${names.size + 1}", used)
        }
      }
      names
    }

    private def tokenizeFrameworkTail(line: String): String = DetailsAttribute.replaceAllIn(line, matched => {
      val details = matched.group(1)
      val framework =
        if (details.startsWith("org.scalatest.")) Some("scalatest")
        else if (details.startsWith("org.specs2.")) Some("specs2")
        else if (details.startsWith("java.lang.AssertionError") || details.startsWith("junit.")) Some("junit")
        else None
      val replacement = framework.flatMap(name => splitFrameworkTail(details).map { case (prefix, tail) =>
        s"details='$prefix{{framework-stack-tail:$name}}'"
      }).getOrElse(matched.matched)
      java.util.regex.Matcher.quoteReplacement(replacement)
    })

    private def splitFrameworkTail(details: String): Option[(String, String)] = {
      val parts = details.split("\\|n", -1).toVector
      val userFrames = parts.zipWithIndex.collect {
        case (frame, index) if frame.startsWith("\tat ") && !isFrameworkFrame(frame.stripPrefix("\tat ")) => index
      }
      userFrames.lastOption.filter(_ < parts.size - 1).map { lastUser =>
        parts.take(lastUser + 1).mkString("|n") -> ("|n" + parts.drop(lastUser + 1).mkString("|n"))
      }
    }

    private def isFrameworkFrame(frame: String): Boolean = Vector(
      "org.scalatest.", "org.specs2.", "org.junit.", "junit.", "com.novocode.junit.", "sbt.", "scala.",
      "java.", "java.base/", "jdk.internal.", "sun.reflect."
    ).exists(frame.startsWith)

    private def isCompilationAnnouncement(line: String): Boolean = {
      val text = if (line.startsWith("[info] ")) Some(line) else serviceMessage(line).flatMap { message =>
        Option(message.getAttributes.get("text"))
      }
      text.exists(_.matches("^\\[info\\] compiling .+ Scala source.*$"))
    }

    private def serviceMessage(line: String): Option[ServiceMessage] =
      if (!line.startsWith("##teamcity[") || !line.endsWith("]")) None
      else try Some(ServiceMessage.parse(line)) catch { case _: ParseException => None }

    private def kebab(value: String): String = value
      .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
      .replaceAll("[^A-Za-z0-9]+", "-")
      .stripPrefix("-").stripSuffix("-").toLowerCase

    private def uniqueName(base: String, used: mutable.Set[String]): String = {
      var candidate = base
      var suffix = 2
      while (!used.add(candidate)) {
        candidate = s"$base-$suffix"
        suffix += 1
      }
      candidate
    }
  }
}
