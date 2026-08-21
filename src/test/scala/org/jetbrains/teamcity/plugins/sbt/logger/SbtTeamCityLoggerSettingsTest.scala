package org.jetbrains.teamcity.plugins.sbt.logger

import org.junit.Assert.assertEquals
import org.junit.Test

class SbtTeamCityLoggerSettingsTest {

  @Test
  def extractsEveryLoggerOptionAndRendersEveryHeaderLine(): Unit = {
    val configured = Map(
      SbtTeamCityLoggerSettings.PreserveConsole.propertyName -> Some("true"),
      SbtTeamCityLoggerSettings.UseTeamCityTestResultLogger.propertyName -> Some("false"),
      SbtTeamCityLoggerSettings.ShowTestTaskOutput.propertyName -> Some("false"),
      SbtTeamCityLoggerSettings.DetailedDependencyResolution.propertyName -> Some("true"),
      SbtTeamCityLoggerSettings.RenderObjectEventDetails.propertyName -> Some("true")
    )

    withLoggerProperties(configured) {
      val settings = SbtTeamCityLoggerSettings.extract()

      assertEquals(true, settings.preserveConsole)
      assertEquals(false, settings.useTeamCityTestResultLogger)
      assertEquals(false, settings.showTestTaskOutput)
      assertEquals(true, settings.detailedDependencyResolution)
      assertEquals(true, settings.renderObjectEventDetails)
      assertEquals(
        Seq(
          "  Preserve SBT console: true",
          "  Use TeamCity test result logger: false (overridden by preserveConsole)",
          "  Show test-task output: false (overridden by preserveConsole)",
          "  Detailed dependency resolution: true",
          "  Render ObjectEvent details: true"
        ),
        settings.headerLines
      )
    }
  }

  @Test
  def hidesInternalSettingsWhenTheyMatchTheirDefault(): Unit = withLoggerProperties(Map(
    SbtTeamCityLoggerSettings.RenderObjectEventDetails.propertyName -> Some("false")
  )) {
    val settings = SbtTeamCityLoggerSettings.extract()

    assertEquals(5, settings.allSettings.size)
    assertEquals(false, settings.renderObjectEventDetails)
    assertEquals(
      Seq(
        "  Preserve SBT console: false (default)",
        "  Use TeamCity test result logger: true (default)",
        "  Show test-task output: true (default)",
        "  Detailed dependency resolution: false (default)"
      ),
      settings.headerLines
    )
    assertEquals(4, settings.headerLines.size)
  }

  private def withLoggerProperties(configured: Map[String, Option[String]])(body: => Unit): Unit = {
    val properties = SbtTeamCityLoggerSettings.all.map(_.propertyName)
    val previousValues = properties.map(property => property -> Option(System.getProperty(property))).toMap
    try {
      properties.foreach { property =>
        configured.get(property).flatten match {
          case Some(value) => System.setProperty(property, value)
          case None => System.clearProperty(property)
        }
      }
      body
    } finally {
      previousValues.foreach {
        case (property, Some(value)) => System.setProperty(property, value)
        case (property, None) => System.clearProperty(property)
      }
    }
  }
}
