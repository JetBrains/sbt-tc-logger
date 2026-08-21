# Contributing to SBT TeamCity Logger

## Prerequisites

Use JDK 17 to run the repository's SBT build and integration-test harness; set `JAVA_HOME` to a JDK 17 installation before invoking `sbt`. \
The host remains SBT 1.12.15 while it builds the logger as concrete SBT 1 and SBT 2 modules against the compatibility baselines SBT 1.4.0/Scala 2.12.21 and SBT 2.0.0/Scala 3.8.4. \
Those are plugin compilation targets, not the concrete nested SBT versions exercised by integration tests.

The SBT 1.x logger is compiled with Java 8 release compatibility so it remains loadable by supported SBT 1 runtimes; the SBT 2.x logger and the build itself run on JDK 17. \
Running the full integration suite also requires an exact local JDK 8 installation. The harness deliberately rejects Java 11 or another newer version in place of JDK 8, and requires an exact JDK 17 installation for its JDK-17 matrix entries.

## Cross-building topology

This repository deliberately does not use SBT's conventional Scala-version cross-build for the logger plugin. Instead, `loggerSbt1` and `loggerSbt2` are concrete projects with fixed Scala/SBT compatibility baselines. Each owns its target-specific sources and directly attaches the shared `src/main/scala` and `src/test/scala` roots.

This is a non-standard structure for an SBT plugin. It is intentional: IntelliJ can import both target classpaths at once, model the common code as shared sources, and expose separate SBT 1 and SBT 2 modules without a developer having to switch the active cross-build target. That gives correct target-specific dependencies and test highlighting while working on either compatibility line.

The trade-off is that shared code and unit tests are compiled once per target, so they must remain source-compatible with both Scala 2.12/SBT 1 and Scala 3/SBT 2. Do not collapse these projects back into a conventional cross-build unless the IDE model can retain those properties.

## Build the logger locally

`sbt publishLocal`

Use this as the main local-development build command. It publishes the compatibility variants to the local Maven repository using artifact filenames derived from their coordinates and version.

The logger sources live directly under `src/`. \
TeamCity service messages are resolved as the managed `org.jetbrains.teamcity:serviceMessages` dependency and packaged into the self-contained logger jar.

## Run integration tests

Integration tests launch nested SBT with the exact, explicit JDK selected by each concrete JUnit class. The outer harness still runs on JDK 17.

Integration tests load the assembled logger jar into nested sbt with sbt's `apply -cp` command. \
The test task automatically assembles and stages both primary artifacts under `target/integration-tests/artifacts/` before launching the harness.

Run the tests:

`sbt test`

### Integration test workflow

1. `sbt test` assembles and stages one self-contained JAR per SBT line under `target/integration-tests/artifacts/`, then enters the Scala/JUnit harness in the `integrationTests` project.
2. Each JUnit test copies its fixture to a scenario-qualified directory under `target/integration-tests/work/<runtime>/`, renders its `sbt.version=@SBT_VERSION@` template with that runtime's concrete version, then starts a fresh nested SBT server for that scenario.
3. The nested command order is: load the logger, configure the scenario cache, run setup commands, print the `sbt-teamcity-logger` status handshake, then run behavior commands. Plain SBT output before the handshake is outside the product contract, but any pre-handshake TeamCity service message fails the test.
4. Fixture-root names express their minimum SBT version: `testdata/1.4+` is the modern SBT 1 corpus, `testdata/1.9+` is the JaCoCo extension, and `testdata/2.0+` is the SBT 2 corpus. Every fixture must contain exactly one `sbt.version=@SBT_VERSION@` property; the harness rejects missing, concrete, or duplicate values before launching SBT.
5. From the handshake through process completion, the harness compares every merged stdout/stderr line with the fixture's exact `expected/<profile>/<scenario-id>.txt` transcript. It also parses every TeamCity-looking line with TeamCity's service-message parser and checks the nested process result independently.

Useful targeted commands:

`sbt testSbt1_4_Jdk8`

`sbt testSbt1_12_Jdk8`

`sbt testSbt1_12_Jdk17`

`sbt testSbt2_0_Jdk17`

`sbt testOther`

### Exact transcript goldens

Goldens are raw wire text: ordinary lines, TeamCity message names, attribute order, escaping, and meaningful version/path suffixes are literal. The format deliberately has no arbitrary regex, includes, or ignored ranges. An intentionally silent behavior must contain the single directive `[[expect-empty]]`; an empty file is invalid.

Typed placeholders cover values which cannot be frozen safely:

- `{{flow:<name>}}` binds one test flow; repeated names must match and different names must remain distinct.
- `{{build-id:<name>}}` binds a numeric build ID while preserving its literal flow suffix.
- `{{path:repo-root}}`, `{{path:work-dir}}`, `{{path:sbt-global-base}}`, `{{path:sbt-ivy-home}}`, `{{path:java-home}}`, and `{{path:user-home}}` replace only known machine-specific roots.
- `{{duration:<name>}}`, `{{timestamp:<name>}}`, `{{thread:<name>}}`, `{{hash:<name>}}`, and `{{logger-version}}` validate their typed values.
- Dependency outcome/metadata and framework stack-tail placeholders are accepted only by their dedicated validators; fixture causes and frames before a recognized framework tail remain literal.

Use an unordered block only for observed concurrent output. Every lane remains internally ordered and every line remains mandatory:

```text
[[unordered]]
[[lane:compile]]
exact first compile line
exact second compile line
[[/lane]]
[[lane:test]]
exact test line
[[/lane]]
[[/unordered]]
```

The only non-literal directives are named strict noise recognizers: `sbt-task-summary`, `sbt-debug-line`, `zinc-debug-message`, `framework-stack-tail`, `dependency-resource-outcome`, `sbt-compiler-bridge`, and `parallel-scalatest-native-summary`. Each recognizer accepts a bounded SBT/Zinc/framework shape and rejects unrelated fixture or plugin output. Prefer literal lines; introduce or widen a recognizer only with focused mutation tests and a nearby rationale.

Candidate commands write only below `target/integration-tests/output-candidates/<profile>/` and finish with `session clear`:

```text
sbt generateSbt1_4_Jdk8OutputCandidates
sbt generateSbt1_12_Jdk8OutputCandidates
sbt generateSbt1_12_Jdk17OutputCandidates
sbt generateSbt2_0_Jdk17OutputCandidates
```

Audit every candidate before copying it beside its source fixture. Preserve pinned SBT, Scala, Zinc, framework, dependency, and tool versions; add one-line or ordered lanes only for repeat-observed concurrency; never convert suspicious logger output into noise. After updating a profile, run its targeted test alias three complete times. The fixture contract tests reject missing, duplicate, empty, unknown-profile, and orphaned goldens, as well as legacy `output*.txt` or `excludes.txt` files.

The integration matrix is intentionally limited rather than a full SBT × JDK cross-product:

| JUnit class | Nested SBT | JDK | Fixture roots |
| --- | --- | --- | --- |
| `SbtLoggerOutput_TestSbt1_4_Jdk8` | 1.4.5 | 8 | `1.4+` |
| `SbtLoggerOutput_TestSbt1_12_Jdk8` | 1.12.15 | 8 | `1.4+`, `1.9+` |
| `SbtLoggerOutput_TestSbt1_12_Jdk17` | 1.12.15 | 17 | `1.4+`, `1.9+` |
| `SbtLoggerOutput_TestSbt2_0_Jdk17` | 2.0.6 | 17 | `2.0+` |

This balance makes the supported SBT 1.4 baseline, current SBT 1 on both supported JDKs, and current SBT 2 meaningful and visible while avoiding the runtime and maintenance cost of combinations that do not add useful compatibility evidence. Future JDK changes intentionally rename the affected concrete class and alias.

The versioned aliases select concrete SBT/JDK matrix entries. `testOther` uses JUnit's category filter to run every auxiliary integration test while excluding the runtime-matrix suites, which inherit the `SbtRuntimeMatrix` category from their shared base class. The JaCoCo scenario runs on the SBT 1.12 classes through `1.9+` and on SBT 2.0 through `2.0+`; `publishTest` remains dormant.

The TeamCity job must require both `env.JDK_1_8_0` and `env.JDK_17_0` before it is scheduled. This matches the explicit matrix and prevents a Java-8 entry from reaching the harness on an incompatible agent.

## TeamCity SBT Runner

The [TeamCity SBT Runner repository](https://github.com/JetBrains/tc-sbt-runner) is the logger's intended consumer. \
It is JetBrains-private, but documents and implements the runner integration that selects the compatible logger artifact and embeds it into TeamCity distributions.

## Publishing the plugin

For the version format, its meaning, and the historical-version context, see the [README versioning section](README.md#versioning). This section covers the maintainer procedure only.

### Version source and release tag

`sbt-dynver` is the sole source of the SBT `version` setting: do not add an explicit `version := ...` setting to the build. On an exact, clean release tag, dynver produces `YYYY.0.PATCH`; on another commit or a dirty checkout, it produces a non-release version.

To create a release after its candidate commit has passed the required checks:

```bash
git tag -a v2026.0.0 -m "Release 2026.0.0"
git push origin v2026.0.0
```

The tag is immutable once publication starts; correct a bad release with a new release version rather than moving or replacing its tag.

### CI release contract

The checked-in CI configuration will be updated separately. Until then, this is the contract that its release workflow must implement:

1. A normal branch or pull-request build runs validation but never publishes to the production repository.
2. A release build starts only for a pushed `vYYYY.0.PATCH` tag.
3. The checkout fetches complete history and tags. A shallow checkout or `--no-tags` clone makes dynver unable to find the release tag.
4. CI verifies that the checkout is clean, `git describe --exact-match --tags HEAD` is the triggering tag, `sbt dynverAssertTagVersion` succeeds, and `sbt 'show version'` equals the tag with its leading `v` removed.
5. CI runs the required test matrix, including the SBT 1.x and SBT 2.x integration tests.
6. Only after those checks pass, CI publishes both logger modules with the same derived version through the aggregate root (`publish`), not a single module invocation.
7. CI records the immutable tag, commit SHA, and published version in the release result. Snapshot publication, if introduced later, must use a separate snapshots repository and must never replace a release artifact.

The release checkout must have Git available and must fetch tags before SBT loads the build. This is required by dynver, not merely by the CI implementation.

## Artifact compatibility

Maven coordinates identify a compatibility variant and the published filename derives from that coordinate and version.

- `org.jetbrains.teamcity.plugins.sbt:sbt-teamcity-logger_2.12_1.0:<logger version>` for SBT 1.x
- `org.jetbrains.teamcity.plugins.sbt:sbt-teamcity-logger_sbt2_3:<logger version>` for SBT 2.x

`prepareIntegrationTestArtifacts` stages the self-contained primary JARs as `target/integration-tests/artifacts/sbt-1.0.jar` and `target/integration-tests/artifacts/sbt-2.jar`. \
The test harness uses only these test-only paths; it does not reconstruct package-output paths or filenames. TeamCity embeds the matching published artifact in each `sbt-distrib/<sbt-line>` directory; Maven publication uses that same self-contained primary artifact with its coordinate- and version-derived filename.

The SBT 2 artifact is deliberately direct-loaded rather than discovered as an SBT plugin. \
Its Maven coordinate follows SBT 2's `_sbt2_3` convention, but the SBT 1.12 host produces it through an explicit compatibility shim; this is not native SBT 2 `SbtPlugin` publication yet. \
Load it with `apply -cp` as shown in the README. \
`publishLocal` for the next major release must publish the SBT 1.x and SBT 2.x compatibility variants above; it must not produce the obsolete `sbt-teamcity-logger_2.10_0.13` variant.

## Maintainer release notes

After this SBT-2-capable logger release is published, upgrade the host build to SBT 2.0.4. \
Until then, TeamCity builds and publishes this repository using the preceding logger release, which cannot run on SBT 2.

Before ending new SBT 0.13 logger production, tag and publish the final SBT-0.13-capable release `L` after its full SBT 0.13 suite has passed. \
`L` is immutable: the TeamCity SBT runner must use its `sbt-teamcity-logger_2.10_0.13:L` artifact for SBT 0.13 rather than a later logger release.

The next major release `N` publishes only the SBT 1 and SBT 2 artifacts listed above. \
`L` and `N` are placeholders for release-time versions, not snapshots or source revisions. Once CalVer starts, use the `YYYY.0.PATCH` format and its matching `vYYYY.0.PATCH` tag. \
After `N` is published and used by TeamCity to build this repository, upgrade this repository's host build to SBT 2.0.4.

The primary published jar is self-contained.
”
