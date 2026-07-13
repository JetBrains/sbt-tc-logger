package jetbrains.buildServer.sbtlogger.utils

object TeamCityOutputNormaliser {
  private val ServiceMessagePrefix = "##teamcity"
  private val ServiceMessagePrefixPatched = "@@teamcity"

  /**
   * IntelliJ IDEA and TeamCity runners parse raw ##teamcity messages printed by this outer test process/<br>
   * We replace it with `@@teamcity` to see it in stdout, and in order they are not processed by the system
   */
  def normaliseNestedServiceMessageOutput(line: String): String =
    line.replace(ServiceMessagePrefix, ServiceMessagePrefixPatched)
}
