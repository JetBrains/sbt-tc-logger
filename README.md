
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

To install the logger manually for local testing with SBT 1.x or SBT 2.x, \
add the following to your project/plugins.sbt file:

```scala
resolvers += "jetbrains-teamcity-repository" at "https://download.jetbrains.com/teamcity-repository"

addSbtPlugin("org.jetbrains.teamcity.plugins.sbt" % "sbt-teamcity-logger" % "<logger version>")
```

## SBT Versions Support

**SBT 1.x and SBT 2.x** use the `addSbtPlugin` installation shown above. \
SBT selects the compatible published coordinate automatically: `_2.12_1.0` for SBT 1.x \
and `_sbt2_3` for SBT 2.x.

**SBT 0.13** is a legacy SBT line. \
New logger releases do not publish an SBT 0.13 artifact. \
For a standalone SBT 0.13 build, pin `sbt-teamcity-logger` to `<final SBT 0.13 logger version>`, \
or upgrade the build to SBT 1 or SBT 2.

In `Auto` installation mode, the TeamCity SBT Runner selects its bundled logger for SBT 0.13, SBT 1.x, or SBT 2.x. \
For SBT 0.13, it uses the final SBT 0.13 logger release. \
The Runner loads the selected logger internally with `apply -cp`.


## Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.

## Development, Testing, and Contributing

For local setup, building, testing, and contribution guidance, see [CONTRIBUTING.md](CONTRIBUTING.md).
