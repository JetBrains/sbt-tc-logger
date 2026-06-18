
[![official JetBrains project](http://jb.gg/badges/official-plastic.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

SBT TeamCity logger
=============

This plugin extends SBT standard output with service messages that TeamCity build server uses to present build results.

You don't need this plugin if you use [TeamCity SBT runner](https://www.jetbrains.com/help/teamcity/simple-build-tool-scala.html) with 'Auto' installation mode.

### Installation

Add the following to your project/plugins.sbt file:

`resolvers += "jetbrains-teamcity-repository" at "<TeamCity Maven repository URL>"`

`addSbtPlugin("org.jetbrains.teamcity.plugins.sbt" % "sbt-teamcity-logger" % "<logger version>")`

or register plugin as a global plugin for your SBT according to [SBT documentation](https://www.scala-sbt.org/1.x/docs/Plugins.html#Plugins)

Plugin is compatible with SBT version 1.x.


### Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.

### Development

Use the repository root as the sbt build. The root build runs on sbt 1.12.12 and builds the logger plugin for both sbt 0.13 and sbt 1.x.

`sbt projects`

`sbt "project logger" "+publishLocal"`

Releases are versioned by Git tags through `sbt-dynver`, for example `v1.1.0`. The public Maven artifacts use the logger release version as the artifact version and put the Scala/sbt binary versions in the artifact id:

- `org.jetbrains.teamcity.plugins.sbt:sbt-teamcity-logger_2.10_0.13:<logger version>`
- `org.jetbrains.teamcity.plugins.sbt:sbt-teamcity-logger_2.12_1.0:<logger version>`

The primary published jar is self-contained. The private `loggerStagingSbt013` and `loggerStagingSbt1` projects are integration-test helpers only; they are not nested Git repositories and are not published.

Integration tests launch old sbt runtimes and require Java 8. Set either `IT_JAVA_HOME` or `JAVA_8_HOME`:

`IT_JAVA_HOME=/path/to/jdk8 sbt test`

The default `test` task builds local logger plugin jars for sbt 0.13 and sbt 1.x, copies them under versioned directories in `target/integration-test-artifacts/tc_plugin`, resolves the required sbt launchers, and runs both integration test suites.

Integration test workflow:

1. `sbt test` enters the Java JUnit harness in the `integrationTests` project.
2. `prepareIntegrationTestArtifacts` first packages self-contained logger plugin jars for sbt 0.13 and sbt 1.x.
3. The packaged jars are staged as `target/integration-test-artifacts/tc_plugin/0.13/sbt-teamcity-logger.jar` and `target/integration-test-artifacts/tc_plugin/1.0/sbt-teamcity-logger.jar`.
4. The test project resolves sbt launcher jars from managed dependencies instead of downloading them with Ant.
5. Each JUnit test starts a nested sbt process for a fixture under `test/testdata` or `test/testdata/1.0`, applies the staged plugin with `apply -cp`, and runs the fixture command.
6. The harness compares nested sbt output with the fixture's `output.txt` regexes and checks `excludes.txt` when present.

Useful targeted commands:

`sbt prepareIntegrationTestArtifacts`

`sbt testSbt013`

`sbt testSbt100`
