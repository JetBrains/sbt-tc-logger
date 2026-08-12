
[![official JetBrains project](http://jb.gg/badges/official-plastic.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

SBT TeamCity logger
=============

This plugin extends SBT standard output with service messages that TeamCity build server uses to present build results.

You don't need this plugin if you use [TeamCity SBT runner](https://www.jetbrains.com/help/teamcity/simple-build-tool-scala.html) with 'Auto' installation mode.

### Reported TeamCity service messages

The plugin reports the following events so TeamCity can display structured SBT build results instead of only plain console output:

- `message` — SBT log output, with error, warning, or normal severity; cancellation notices are reported this way too.
- `compilationStarted` and `compilationFinished` — the start and end of Scala compilation for main and test sources.
- `inspectionType` and `inspection` — compiler problems, including their severity, source file, and line, so TeamCity can show them as build inspections.
- `testSuiteStarted` and `testSuiteFinished` — the lifecycle and outcome of each test suite, including suite-level errors.
- `testStarted` and `testFinished` — the lifecycle, duration, and captured standard output of each test.
- `testFailed` and `testIgnored` — failed tests with exception details, and skipped, ignored, pending, or cancelled tests.

Messages include flow IDs where needed, allowing TeamCity to associate output and test events correctly during parallel execution.

## Installation

Add the following to your project/plugins.sbt file:

`resolvers += "jetbrains-teamcity-repository" at "<TeamCity Maven repository URL>"`

`addSbtPlugin("org.jetbrains.teamcity.plugins.sbt" % "sbt-teamcity-logger" % "<logger version>")`

or register plugin as a global plugin for your SBT according to [SBT documentation](https://www.scala-sbt.org/1.x/docs/Plugins.html#Plugins)

New logger releases support SBT 1.x and SBT 2.x. \
SBT 1 uses the standard `addSbtPlugin` installation shown above. \
SBT 2 uses the self-contained direct-load jar:
`apply -cp <path-to>/sbt-teamcity-logger_sbt2_3-<logger version>.jar jetbrains.buildServer.sbtlogger.SbtTeamCityLogger`

The TeamCity SBT runner performs that direct load automatically in `Auto` installation mode. It embeds both runtime
variants under their SBT-line directories as `sbt-teamcity-logger.jar`; the directory, not the filename, selects the
compatible JAR.

SBT 0.13 is supported by the runner through the immutable final legacy release `L`
(`sbt-teamcity-logger_2.10_0.13:L`). New logger releases do not publish an SBT 0.13 artifact;
upgrade standalone SBT 0.13 installations to the pinned `L` release, or upgrade the build to SBT 1 or SBT 2.


## Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.

## Development, Testing, and Contributing

For local setup, building, testing, and contribution guidance, see [CONTRIBUTING.md](CONTRIBUTING.md).
