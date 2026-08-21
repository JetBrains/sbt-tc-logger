# SBT TeamCity Logger glossary

Use this vocabulary in code, Scaladoc, tests, and reviews.

## Protocol and presentation

- **SBT log event** — a severity-tagged event from SBT's logging API. The task-log appender forwards it to the
  Build Log path.
- **TeamCity service message** — a typed `TeamCityServiceMessage` rendered as one `##teamcity[...]` protocol line.
- **TeamCity service-message writer** — `TeamCityServiceMessageWriter`, the SBT-agnostic output boundary. The
  standard-output writer is the production implementation.
- **TeamCity build event** — a semantic SBT integration event that changes TeamCity's Build Log, such as a log
  entry, a block transition, or a compilation lifecycle transition. `SbtBuildEventReporter` translates these to
  typed protocol messages; it does not represent Tests or Code Inspections events.
- **Build Log message** — a TeamCity `message`, block, or compilation-lifecycle protocol message that changes the
  Build Log presentation.
- **Test event** — a test-suite or individual-test lifecycle message rendered in TeamCity's Tests tab.
- **Compiler inspection** — an inspection-type or inspection message derived from an SBT/Zinc compiler problem and
  rendered in Code Inspections.
- **Flow ID** — the protocol attribute that associates concurrent SBT task output or test-worker events with their
  TeamCity flow.

## SBT integration roles

- **SBT Build Log reporter** — `SbtBuildEventReporter`, the stateful coordinator for ordinary task output and
  compiler lifecycle presentation.
- **SBT task-log appender** — `SbtTaskLogAppender`, the only project type that extends SBT's `ConsoleAppender`.
  It converts ordinary SBT task log events to Build Log reporting.
- **SBT dependency-resolution reporter** — `SbtDependencyResolutionReporter`, which owns one concurrent Coursier
  update wave; `SbtCoursierDependencyEventReporter` is its per-task Coursier callback bridge.
- **Test-result logger** — an SBT `TestResultLogger`; `SbtTestResultLoggerAdapter` runs one against the Build Log
  reporter and is not responsible for structured test events.
- **SBT-private bridge** — `sbt.org.jetbrains.teamcity.plugins.sbt.logger.internal`, the small package retained only
  for APIs SBT exposes as `private[sbt]`.

## Package taxonomy and naming

- `…logger.serviceMessages` is SBT-agnostic protocol code only. It must not import SBT, Zinc, or Coursier.
- `…logger.buildLog` owns Build Log presentation. `…logger.reporting` owns Tests and Code Inspections.
  Root `…logger` adapters coordinate the one Zinc callback that needs both paths.
- Spell product and build-tool names as `TeamCity` and `Sbt` in symbols.
- Use **appender** only for a type extending SBT `Appender` or `ConsoleAppender`. Use **logger** only for the plugin,
  an SBT `Logger`, or an SBT `TestResultLogger`.
- Keep `SbtTeamCityLogger`, `SbtTeamCityLoggerSettings`, `sbt-teamcity-logger`, artifact coordinates, and
  `teamcity.sbt.logger.*` property strings stable. Other implementation FQCNs are internal and may change.
