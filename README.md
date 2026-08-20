
[![official JetBrains project](http://jb.gg/badges/official-plastic.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

SBT TeamCity logger
=============

This plugin extends SBT standard output with service messages that TeamCity build server uses to present build results.

You don't need this plugin if you use [TeamCity SBT runner](https://www.jetbrains.com/help/teamcity/simple-build-tool-scala.html) with 'Auto' installation mode.

### Reported TeamCity service messages

The plugin reports the following events so TeamCity can display structured SBT build results instead of only plain console output:

- `message` — each SBT logger event exactly once, with its original `[debug]`, `[info]`, `[warn]`, or `[error]` prefix and TeamCity severity.
- `blockOpened` and `blockClosed` — dependency-resolution phases, displayed as `Dependency resolution [project]` (and the Test equivalent).
- `compilationStarted` and `compilationFinished` — the start and end of visible Scala/Java compiler output for main and test sources, displayed as `Scala compiler [project]`. Up-to-date and no-source compilations do not create empty compiler blocks.
- `inspectionType` and `inspection` — compiler problems, including their severity, source file, and line, so TeamCity can show them as build inspections.
- `testSuiteStarted` and `testSuiteFinished` — the lifecycle and outcome of each test suite, including suite-level errors.
- `testStarted` and `testFinished` — the lifecycle, duration, and captured standard output of each test.
- `testFailed` and `testIgnored` — failed tests with exception details, and skipped, ignored, pending, or cancelled tests.

Messages include phase- and configuration-specific flow IDs, allowing TeamCity to associate output and test events correctly during parallel execution. Dependency resolution deliberately does not live inside a compiler block: TeamCity treats errors inside a `compilationStarted`/`compilationFinished` pair as compiler errors.

## Installation

To install the logger manually for local testing with SBT 1.x or SBT 2.x, \
add the following to your project/plugins.sbt file:

```scala
resolvers += "jetbrains-teamcity-repository" at "https://download.jetbrains.com/teamcity-repository"

addSbtPlugin("org.jetbrains.teamcity.plugins.sbt" % "sbt-teamcity-logger" % "<logger version>")
```

## SBT Versions Support

**SBT 1.4+ and SBT 2.x** use the `addSbtPlugin` installation shown above. \
SBT selects the compatible published coordinate automatically: `_2.12_1.0` for SBT 1.x \
and `_sbt2_3` for SBT 2.x.

**SBT 0.13** is a legacy SBT line. \
New logger releases do not publish an SBT 0.13 artifact. \
For a standalone SBT 0.13 build, pin `sbt-teamcity-logger` to `<final SBT 0.13 logger version>`, \
or upgrade the build to SBT 1 or SBT 2.

In `Auto` installation mode, the TeamCity SBT Runner selects its bundled logger for SBT 0.13, SBT 1.x, or SBT 2.x. \
For SBT 0.13, it uses the final SBT 0.13 logger release. \
The Runner loads the selected logger internally with `apply -cp`.

## Versioning

The logger uses calendar versioning (CalVer) in the Maven-compatible form `YYYY.0.PATCH`. \
The version describes this independently published SBT plugin; it is not a TeamCity server version, TeamCity build number, or SBT runtime version.

### Current scheme

Starting with `2026.0.0`, releases use the following rules:

- `YYYY` is the calendar year in which the release is tagged and published.
- The middle component is reserved as `0`.
- `PATCH` starts at `0` each year and increases for every later release in that year: `2026.0.0`, `2026.0.1`, and so on.
- The Git release tag is `vYYYY.0.PATCH`, for example `v2026.0.0`. `sbt-dynver` derives the published version by removing the `v`.

The year is a release-date convenience, not a compatibility boundary. \
For example, a TeamCity runner released in 2027 may use `2026.0.0` when that logger version remains suitable. \
The version has no direct correlation with a TeamCity release version: a logger update may happen because of work for a recent TeamCity release in the same year, but `2026.0.0` neither targets nor requires a particular TeamCity `2026.x` release. \
Both the SBT 1.x and SBT 2.x artifact coordinates for one release use the same logger version.

### Historical versions

The historical versioning story was inconsistent. Early builds hard-coded `0.1.0-SNAPSHOT`; later builds introduced `sbt-dynver`; and the public repository does not retain an authoritative release-tag history. \
Consequently, old version numbers do not provide a reliable timeline or compatibility policy. They also predate the current split between SBT 0.13, SBT 1.x, and SBT 2.x artifacts.

Existing published versions remain valid for consumers that need them, especially the final SBT 0.13-compatible logger. \
New releases start the documented CalVer sequence at `2026.0.0`; do not infer a relationship between this sequence and the earlier ad-hoc versions.


## Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.

By default the plugin replaces SBT's task-console renderer so TeamCity receives one structured representation of each SBT logger event. SBT's backing logs (for example, `last`) remain available. `println`, external-process output, and build-load output produced before the plugin is applied are not SBT logger events and remain raw by design.

If a build must keep SBT's normal log manager, start SBT with `-Dteamcity.sbt.logger.preserveConsole=true`. This observer mode preserves ordinary task, compiler, and test-result output: the logger does not replace `logManager`, suppress the default compiler reporter, or install its silent test-result logger. It still reports compiler problems as TeamCity inspections and publishes test events, but does not create TeamCity dependency or `Scala compiler` blocks.

## Development, Testing, and Contributing

For local setup, building, testing, and contribution guidance, see [CONTRIBUTING.md](CONTRIBUTING.md).
