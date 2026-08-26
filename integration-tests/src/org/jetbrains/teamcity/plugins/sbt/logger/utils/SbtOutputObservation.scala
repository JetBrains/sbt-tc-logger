package org.jetbrains.teamcity.plugins.sbt.logger.utils

import jetbrains.buildServer.messages.serviceMessages.{ServiceMessage, ServiceMessageParserCallback, ServiceMessagesParser}

import java.text.ParseException
import scala.collection.immutable.VectorMap
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** The layer which rejected an observed run. */
private[logger] enum SbtVerificationFailureCategory {
  case GoldenSyntaxFailure
  case WireProtocolFailure
  case SemanticCardinalityFailure
  case FlowOwnershipFailure
  case LifecycleFailure
  case OrderingFailure
  case PlainOutputFailure
  case MatcherComplexityFailure
  case ProcessResultFailure
}

private[logger] enum SbtFindingDisposition {
  case Violation
  case Blocked
}

private[logger] final case class SbtSemanticFailure(
  category: SbtVerificationFailureCategory,
  summary: String,
  context: Vector[String] = Vector.empty,
  disposition: SbtFindingDisposition = SbtFindingDisposition.Violation,
  semanticIdentity: String = "run"
) {
  def render: String = {
    val header = s"[$category] $summary"
    (header +: context).map(TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput).mkString("\n")
  }
}

private[logger] final class SbtSemanticVerificationException(val findings: Vector[SbtSemanticFailure])
  extends AssertionError(SbtSemanticVerificationException.render(findings)) {
  require(findings.nonEmpty, "A semantic verification exception requires at least one finding.")
  def failures: Vector[SbtSemanticFailure] = findings
  def failure: SbtSemanticFailure = findings.head
}

private[logger] object SbtSemanticVerificationException {
  def apply(failure: SbtSemanticFailure): SbtSemanticVerificationException =
    new SbtSemanticVerificationException(Vector(failure))

  private def render(failures: Vector[SbtSemanticFailure]): String = {
    val violationCount = failures.count(_.disposition == SbtFindingDisposition.Violation)
    val blockedCount = failures.count(_.disposition == SbtFindingDisposition.Blocked)
    val heading = s"Semantic output verification found ${failures.size} findings " +
      s"($violationCount violations, $blockedCount blocked):"
    val rendered = failures
      .groupBy(_.category)
      .toVector
      .sortBy(_._1.toString)
      .flatMap { case (category, categoryFindings) =>
        val identities = categoryFindings
          .groupBy(_.semanticIdentity)
          .toVector
          .sortBy(_._1)
          .flatMap { case (identity, identityFindings) =>
            val details = identityFindings.sortBy(finding => (finding.disposition.toString, finding.summary)).map { finding =>
              val body = (finding.summary +: finding.context).map(
                TeamCityOutputNormaliser.normaliseNestedServiceMessageOutput
              ).mkString("\n        ")
              s"      - [${finding.disposition}] $body"
            }
            s"    $identity" +: details
          }
        s"  [$category]" +: identities
      }
    (heading +: rendered).mkString("\n")
  }
}

/** Typed classification of an officially parsed TeamCity service message. */
private[logger] sealed trait ObservedServiceMessageKind {
  def wireName: String
  def isLifecycleBoundary: Boolean = false
}

private[logger] object ObservedServiceMessageKind {
  case object BuildLogMessage extends ObservedServiceMessageKind { val wireName = "message" }
  case object CompilationStarted extends ObservedServiceMessageKind {
    val wireName = "compilationStarted"
    override val isLifecycleBoundary = true
  }
  case object CompilationFinished extends ObservedServiceMessageKind {
    val wireName = "compilationFinished"
    override val isLifecycleBoundary = true
  }
  case object BlockOpened extends ObservedServiceMessageKind {
    val wireName = "blockOpened"
    override val isLifecycleBoundary = true
  }
  case object BlockClosed extends ObservedServiceMessageKind {
    val wireName = "blockClosed"
    override val isLifecycleBoundary = true
  }
  case object TestSuiteStarted extends ObservedServiceMessageKind {
    val wireName = "testSuiteStarted"
    override val isLifecycleBoundary = true
  }
  case object TestSuiteFinished extends ObservedServiceMessageKind {
    val wireName = "testSuiteFinished"
    override val isLifecycleBoundary = true
  }
  case object TestStarted extends ObservedServiceMessageKind {
    val wireName = "testStarted"
    override val isLifecycleBoundary = true
  }
  case object TestFinished extends ObservedServiceMessageKind {
    val wireName = "testFinished"
    override val isLifecycleBoundary = true
  }
  case object TestFailed extends ObservedServiceMessageKind { val wireName = "testFailed" }
  case object TestIgnored extends ObservedServiceMessageKind { val wireName = "testIgnored" }
  case object InspectionType extends ObservedServiceMessageKind { val wireName = "inspectionType" }
  case object Inspection extends ObservedServiceMessageKind { val wireName = "inspection" }
  final case class Other(wireName: String) extends ObservedServiceMessageKind

  private val Known: Vector[ObservedServiceMessageKind] = Vector(
    BuildLogMessage,
    CompilationStarted,
    CompilationFinished,
    BlockOpened,
    BlockClosed,
    TestSuiteStarted,
    TestSuiteFinished,
    TestStarted,
    TestFinished,
    TestFailed,
    TestIgnored,
    InspectionType,
    Inspection
  )

  def fromWireName(name: String): ObservedServiceMessageKind =
    Known.find(_.wireName == name).getOrElse(Other(name))
}

private[logger] sealed trait ObservedOutput {
  /** Zero-based index in the source output supplied to the parser, including any explicit offset. */
  def sourceIndex: Int
  def rawLine: String
  final def sourceLineNumber: Int = sourceIndex + 1
}

private[logger] final case class ObservedPlainLine(sourceIndex: Int, rawLine: String) extends ObservedOutput

private[logger] final case class ObservedMalformedTeamCityLine(
  sourceIndex: Int,
  rawLine: String,
  details: String
) extends ObservedOutput {
  def failure: SbtSemanticFailure = SbtSemanticFailure(
    SbtVerificationFailureCategory.WireProtocolFailure,
    s"Malformed TeamCity-looking output at source line $sourceLineNumber: $details.",
    Vector(s"Raw: $rawLine"),
    semanticIdentity = s"wire:line-$sourceLineNumber"
  )
}

/**
 * A lossless semantic view over an official TeamCity parser result.
 *
 * `officialMessage` is retained deliberately: exact-wire canaries can still inspect parser behavior which is not
 * represented in the semantic classification. `attributes` is an immutable snapshot in parser iteration order.
 */
private[logger] final case class ObservedServiceMessage(
  sourceIndex: Int,
  rawLine: String,
  kind: ObservedServiceMessageKind,
  attributes: VectorMap[String, String],
  officialMessage: ServiceMessage
) extends ObservedOutput {
  def attribute(name: String): Option[String] = attributes.get(name)
  def flowId: Option[String] = attribute("flowId")
}

private[logger] final case class ObservedHandshake(loggerVersion: String)

private[logger] final case class ObservedRun(
  handshake: Option[ObservedHandshake],
  output: Vector[ObservedOutput],
  exitCode: Int
) {
  val serviceMessages: Vector[ObservedServiceMessage] = output.collect { case message: ObservedServiceMessage => message }
  val plainOutput: Vector[ObservedPlainLine] = output.collect { case line: ObservedPlainLine => line }
  val wireFailures: Vector[SbtSemanticFailure] = output.collect { case line: ObservedMalformedTeamCityLine => line.failure }
}

/** Parses the already bounded product transcript without normalising or rewriting any source data. */
private[logger] object SbtOutputObservation {
  def parseBounded(
    lines: Vector[String],
    exitCode: Int,
    loggerVersion: Option[String] = None,
    sourceIndexOffset: Int = 0
  ): ObservedRun = {
    require(sourceIndexOffset >= 0, s"Negative source index offset: $sourceIndexOffset.")

    val output = lines.zipWithIndex.map { case (line, relativeIndex) =>
      val sourceIndex = sourceIndexOffset + relativeIndex
      if (line.contains("##teamcity[")) {
        parseTeamCityLine(line, sourceIndex).fold(
          details => ObservedMalformedTeamCityLine(sourceIndex, line, details),
          identity
        )
      }
      else ObservedPlainLine(sourceIndex, line)
    }
    ObservedRun(loggerVersion.map(ObservedHandshake.apply), output, exitCode)
  }

  private def parseTeamCityLine(line: String, sourceIndex: Int): Either[String, ObservedServiceMessage] = {
    if (!line.startsWith("##teamcity[") || !line.endsWith("]")) {
      return Left("the marker must occupy one complete output line")
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
        if (unparsedText.nonEmpty) s"unparsed text: ${unparsedText.mkString}"
        else s"parsed ${messages.size} service messages"
      }
      return Left(details)
    }

    val message = messages.head
    val attributes = VectorMap.from(message.getAttributes.asScala.iterator)
    Right(ObservedServiceMessage(
      sourceIndex = sourceIndex,
      rawLine = line,
      kind = ObservedServiceMessageKind.fromWireName(message.getMessageName),
      attributes = attributes,
      officialMessage = message
    ))
  }
}
