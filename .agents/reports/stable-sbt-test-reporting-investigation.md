# Stable SBT test reporting investigation

Date: 2026-08-20

## Outcome

The corrected direction is implemented and validated without changing `TCReportListener`.

- SBT's test-interface lifecycle callbacks stayed suite-affine in the tested ScalaTest, JUnit 4, Jupiter, and MUnit flows.
- Removing/filtering the TeamCity listener during the earlier runtime experiments did not remove ScalaTest's intermittent native summary, so the listener is not the cause of that output.
- The ScalaTest 3.2.20 duplicate-`suiteId` registry affects native summary ordering, not the structured TeamCity transcript.
- SBT 2 implements public `testOnly` behavior through internal `testSelected`. Muting and result-logger replacement must therefore cover `testSelected` as well.
- The two controls remain independent and default to the current effective value, `true`.

`TCReportListener` and published listener APIs are unchanged.

## Implemented logger correction

- Added SBT 2 `testSelected` to every project configuration's muted test-task keys and scoped `TestResultLogger` replacement.
- Retained the failure-preserving silent TeamCity result logger and configured-result-logger delegation.
- Removed the `parallel-scalatest-native-summary` recognizer, renderer path, verifier tests, and optional golden atoms.
- Kept the duplicate ScalaTest suite IDs. Its regression now runs with `showTestTaskOutput=false` and an exact structured transcript.
- Added SBT 1.12/SBT 2 exact Scala 3.8.4 fixtures for:
  - ScalaTest 3.2.20;
  - JUnit 4.13.2 with `junit-interface` 0.13.3;
  - Jupiter 6.0.3 with SBT adapters 0.19.0;
  - MUnit 1.3.5.
- MUnit uses `Tests.Argument("+l")`, which routes its native result reporting through SBT logging so the task-output control can govern it consistently.
- Added exact control-matrix coverage for `testOnly`, `testQuick`, SBT 2 `testFull`, a custom configuration, and a custom no-throw result logger.
- Added a generated SBT 2 per-fixture `localCacheDirectory` setting before initial project load. This forces real task execution without a second settings reapplication, so recorded transcripts cannot silently reuse another workspace's task result.

## Framework/runtime findings

### ScalaTest

ScalaTest 3.2.20 produced stable structured test events on SBT 1.12.15 and SBT 2.0.6. Duplicate suite IDs are retained in the parallel regression. Native summaries persisted when the TeamCity listener was experimentally removed, confirming that `TCReportListener` should not be rewritten to address them.

### JUnit

Both legacy JUnit 4.13.2/interface 0.13.3 and Jupiter 6.0.3/adapters 0.19.0 produced exact structured events on both SBT lines. SBT 2 `testOnly` exposed the internal `testSelected` delegation: after the corrected silent logger is applied there, the redundant native three-line failure summary disappears but `TestsFailedException` and exit 1 remain.

### MUnit

MUnit 1.3.5 produced exact structured events on both SBT lines. With `+l`, its ordinary result output travels through SBT's logging channel and obeys `showTestTaskOutput`; structured TeamCity events remain independent.

## Repository validation

All verbose logs are under `target/investigation/`.

| Validation | Result | Log |
|---|---:|---|
| Transcript verifier + fixture contracts | 18/18 passed | `verify-transcript-contracts.log` |
| SBT 2 fixture-cache workspace | 9/9 passed | `verify-sbt2-fixture-cache-workspace.log` |
| SBT 1.12.15/JDK 17 reporting, controls, status, preserveConsole | 32/32 passed | `verify-reporting-controls-sbt1-final.log` |
| SBT 2.0.6/JDK 17 reporting, controls, status, preserveConsole | 35/36 passed before correcting the one affected legacy `testOnly` golden | `verify-reporting-controls-sbt2-final.log` |
| Corrected SBT 2 default `testOnly` golden | 1/1 passed | `verify-sbt2-default-test-only-final.log` |
| SBT 2 non-default control matrix | 11/11 passed | `verify-control-matrix-sbt2-run3.log` |

The SBT 2 aggregate result is therefore all 36 selected cases covered: 35 unchanged cases passed in the sweep, and the only changed golden passed immediately afterward in isolation.

Focused runner validation:

```text
./mvnw -pl tests -am \
  -Dtest=SbtRunnerBuildServiceCommandLineTest,SbtRunnerTypeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: 20 tests passed, no failures. Log: `/Users/dmitrii.naumenko/Desktop/dev/tc-sbt-runner-test-output-controls/target/focused-test-logging-controls.log`.

This covers defaults, absent-value compatibility, false-only JVM propagation, preservation under `preserveConsole`, editor disabling, read-only saved/default/override rendering, descriptions, and Kotlin DSL exposure. No runner correction was required.

## Disposable local TeamCity

Deployed logger version:

```text
2026.0.0-M5+24-22e444c9+20260820-1141
```

Deployment used the repository helper with the isolated logger and runner worktrees. It published both `sbt-teamcity-logger_2.12_1.0` and `sbt-teamcity-logger_sbt2_3`, rebuilt the runner, replaced only the disposable SDK's bundled plugin ZIP, and started its local agent. Log: `target/investigation/local-teamcity-deploy.log`.

All four demo configurations expose reusable `teamcity.sbt.logger.useTeamCityTestResultLogger`, `teamcity.sbt.logger.showTestTaskOutput`, and `teamcity.sbt.logger.preserveConsole` parameters. Each build contains SBT 2.0.6, 1.12.15, 1.11.7, and 1.4.9 steps.

### Failing-test control matrix

Job: `build_tools_demo_SbtTestDemoCompilationSuccesfulTestFailures`

| Build | use TC result logger | show task output | preserveConsole | Per-version SBT exit | Structured TeamCity result |
|---:|---:|---:|---:|---|---|
| 1601 | true | true | false | 1, 1, 1, 1 | 9 tests: 7 passed, 2 failed |
| 1602 | true | false | false | 1, 1, 1, 1 | same names/statuses |
| 1603 | false | true | false | 1, 1, 1, 1 | same names/statuses |
| 1604 | false | false | false | 1, 1, 1, 1 | same names/statuses |
| 1605 | false (saved) | false (saved) | true | 1, 1, 1, 1 | same names/statuses |

The TeamCity test endpoint returned an identical sorted `(name, status)` set for builds 1601-1605. TeamCity collapses the repeated names from the four version steps into nine unique tests, while the four step exits prove every version ran and failed as intended.

Output-channel observations:

| Build | Ordinary suite lines | Configured result summaries | `TestsFailedException` log line | Observation |
|---:|---:|---:|---:|---|
| 1601 | 12 | 0 | 4 | task output visible; TeamCity result logger silent |
| 1602 | 0 | 0 | 0 | all ordinary test-task output hidden; exits still 1 |
| 1603 | 12 | 12 | 4 | configured logger and task output visible |
| 1604 | 0 | 12 | 0 | task output hidden; configured logger remains independently visible |
| 1605 | 12 | 12 | 4 | `preserveConsole` shows default SBT output and status marks both saved false values as overridden |

Raw build logs are `target/investigation/teamcity-build-1601.log` through `teamcity-build-1605.log`; structured results are `teamcity-tests-1601.json` through `teamcity-tests-1605.json`.

### Default smoke builds

| Build | Configuration | Result |
|---:|---|---|
| 1606 | six-module compilation | all four SBT steps exited 1 on intentional compilation failures; 40 inspections, 24 errors |
| 1607 | compilation errors + test failures | all four SBT steps exited 1; 9 structured tests (7 passed, 2 failed), 60 inspections, 36 errors |
| 1608 | JUnit aggregate only | 6 structured tests (4 passed, 2 failed); SBT 1.12/1.11/1.4 exited 1 |

Raw logs: `teamcity-build-1606.log` through `teamcity-build-1608.log`. Structured results: `teamcity-tests-1606.json` through `teamcity-tests-1608.json`.

## Representative commands

```text
# Exact repository reporting/control sweep (the class changes for SBT 2)
sbt --client ";project integrationTests; testOnly \
  jetbrains.buildServer.sbtlogger.SbtLoggerOutput_TestSbt1_12_Jdk17 \
  -- --tests=pluginStatus_ReportsConfiguredLoggerOptions,\
taskLogging_CustomLogManagerCanBePreservedExplicitly,testReporting_.*"

# Local deployment
JAVA_HOME=<jdk17> \
TC_SBT_LOGGER_REPOSITORY_DIR=/Users/dmitrii.naumenko/Desktop/dev/sbt-tc-logger-test-output-controls \
/usr/bin/env bash \
  /Users/dmitrii.naumenko/Desktop/dev/tc-sbt-runner-test-output-controls/scripts/\
publish-local-sbt-tc-logger-and-restart-teamcity.sh

# Matrix queue shape
teamcity run start build_tools_demo_SbtTestDemoCompilationSuccesfulTestFailures \
  -P teamcity.sbt.logger.useTeamCityTestResultLogger=<true|false> \
  -P teamcity.sbt.logger.showTestTaskOutput=<true|false> \
  -P teamcity.sbt.logger.preserveConsole=<true|false>
```

## Remaining risks and scope

- Scope is SBT 1.4+; legacy logger artifacts may ignore the properties.
- Repository validation intentionally used one JDK target (JDK 17) for SBT 1.12 and SBT 2, as requested.
- The SBT 2 compiler and test compilation starts are genuinely concurrent. Only those two start lines use narrowly scoped unordered verifier lanes; all other transcript lines remain exact.
- In smoke build 1608, the SBT 2 `junitRoot/testFull` step compiled the JUnit modules but discovered no tests and exited 0. This is a limitation of that legacy aggregate demo/adapter combination, not of the logger controls. Dedicated SBT 2 exact fixtures for both JUnit 4 and Jupiter passed and are the regression coverage for modern SBT 2 JUnit reporting.
- Local TeamCity used warm artifact caches (`sbt.demo.freshArtifactCaches=false`). Repository integration tests independently force scenario-local SBT 2 task caches, clean fixture workspaces, and exact output verification.

## Delivery state

- Logger worktree: `/Users/dmitrii.naumenko/Desktop/dev/sbt-tc-logger-test-output-controls`
- Runner worktree: `/Users/dmitrii.naumenko/Desktop/dev/tc-sbt-runner-test-output-controls`
- Runner commit remains `abb1666`.
- The logger changes and this report are committed together on `codex/test-output-controls` and mirrored in `sbt2-WIP`.
- Nothing is pushed or merged, and unrelated local changes in the original dirty checkouts remain untouched.
