/*
 * Copyright 2013-2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.sbtlogger

/**
 * Catalogues every user-facing and internal logger VM option and extracts the values observed by the plugin.
 *
 * The status command renders [[Values.headerLines]], so user-facing settings and non-default internal settings in
 * [[all]] are visible in the startup header as well as available to the logger implementation. The TeamCity
 * environment marker and the reload guard are kept here too because they are the other external inputs the plugin
 * recognises.
 */
object SbtTeamCityLoggerSettings {
  final case class BooleanSetting(
    propertyName: String,
    displayName: String,
    defaultValue: Boolean,
    overriddenByPreserveConsole: Boolean = false,
    internal: Boolean = false
  ) {
    def extract(): BooleanValue = {
      val rawValue = Option(System.getProperty(propertyName))
      BooleanValue(this, valueOf(rawValue), rawValue.isDefined)
    }

    def isEnabled: Boolean = valueOf(Option(System.getProperty(propertyName)))

    private def valueOf(rawValue: Option[String]): Boolean =
      rawValue.fold(defaultValue)(java.lang.Boolean.parseBoolean)
  }

  final case class BooleanValue(
    setting: BooleanSetting,
    value: Boolean,
    explicitlyConfigured: Boolean
  ) {
    def matchesDefault: Boolean = value == setting.defaultValue

    private[sbtlogger] def render(overridden: Boolean): String = {
      val defaultHint = if (!explicitlyConfigured && value == setting.defaultValue) Seq("default") else Nil
      val overriddenHint = if (overridden) Seq("overridden by preserveConsole") else Nil
      val hints = defaultHint ++ overriddenHint
      val hintsText = if (hints.nonEmpty) s" (${hints.mkString("; ")})" else ""
      s"$value$hintsText"
    }
  }

  final class Values(
    val teamCityVersion: Option[String],
    val allSettings: Seq[BooleanValue]
  ) {
    private val valuesBySetting = allSettings.map(value => value.setting -> value).toMap

    def valueOf(setting: BooleanSetting): Boolean = valuesBySetting(setting).value

    def preserveConsole: Boolean = valueOf(PreserveConsole)
    def useTeamCityTestResultLogger: Boolean = valueOf(UseTeamCityTestResultLogger)
    def showTestTaskOutput: Boolean = valueOf(ShowTestTaskOutput)
    def detailedDependencyResolution: Boolean = valueOf(DetailedDependencyResolution)
    def renderObjectEventDetails: Boolean = valueOf(RenderObjectEventDetails)

    /** Every setting that should be shown in the stable user-facing header order. */
    def headerLines: Seq[String] = allSettings.filter { current =>
      !current.setting.internal || !current.matchesDefault
    }.map { current =>
      val overridden = current.setting.overriddenByPreserveConsole && preserveConsole
      s"  ${current.setting.displayName}: ${current.render(overridden)}"
    }
  }

  val TeamCityVersionEnvironmentVariable = "TEAMCITY_VERSION"
  val LoggerLoadStateProperty = "TEAMCITY_SBT_LOGGER_VERSION"

  val PreserveConsole = BooleanSetting(
    propertyName = "teamcity.sbt.logger.preserveConsole",
    displayName = "Preserve SBT console",
    defaultValue = false
  )
  val UseTeamCityTestResultLogger = BooleanSetting(
    propertyName = "teamcity.sbt.logger.useTeamCityTestResultLogger",
    displayName = "Use TeamCity test result logger",
    defaultValue = true,
    overriddenByPreserveConsole = true
  )
  val ShowTestTaskOutput = BooleanSetting(
    propertyName = "teamcity.sbt.logger.showTestTaskOutput",
    displayName = "Show test-task output",
    defaultValue = true,
    overriddenByPreserveConsole = true
  )
  val DetailedDependencyResolution = BooleanSetting(
    propertyName = "teamcity.sbt.logger.detailedDependencyResolution",
    displayName = "Detailed dependency resolution",
    defaultValue = false
  )
  val RenderObjectEventDetails = BooleanSetting(
    propertyName = "teamcity.sbt.logger.renderObjectEventDetails",
    displayName = "Render ObjectEvent details",
    defaultValue = false,
    internal = true
  )

  /** The complete catalog of VM options that alter logger behaviour, including internal developer options. */
  val all: Seq[BooleanSetting] = Seq(
    PreserveConsole,
    UseTeamCityTestResultLogger,
    ShowTestTaskOutput,
    DetailedDependencyResolution,
    RenderObjectEventDetails
  )

  /** The reload guard is intentionally read live because plugin initialization updates it before [[AutoPlugin.apply]]. */
  def loggerLoadState: Option[String] = Option(System.getProperty(LoggerLoadStateProperty))

  def extract(): Values = {
    val extractedSettings = all.map(_.extract())
    new Values(
      teamCityVersion = Option(System.getenv(TeamCityVersionEnvironmentVariable)),
      allSettings = extractedSettings
    )
  }
}
