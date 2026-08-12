# Contributing to SBT TeamCity Logger

## Prerequisites

Use JDK 17 to run the repository's SBT build and integration-test harness; set `JAVA_HOME` to a JDK 17 installation before invoking `sbt`. \
The host remains SBT 1.12.12 while it cross-builds the logger against the stable line baselines SBT 1.12.0/Scala 2.12.21 and SBT 2.0.0/Scala 3.8.4. \
Those are plugin compilation targets, not the concrete nested SBT versions exercised by integration tests.

The SBT 1.x logger is compiled with Java 8 release compatibility so it remains loadable by supported SBT 1 runtimes; the SBT 2.x logger and the build itself run on JDK 17. \
Running the full integration suite also requires an exact local JDK 8 installation. The harness deliberately rejects Java 11 or another newer version in place of JDK 8, and requires an exact JDK 17 installation for its JDK-17 matrix entries.

## Build the logger locally

`sbt +publishLocal`

Use this as the main local-development build command. It publishes the compatibility variants to the local Maven repository using artifact filenames derived from their coordinates and version.

The logger sources live directly under `src/`. \
TeamCity service messages are resolved as the managed `org.jetbrains.teamcity:serviceMessages` dependency and packaged into the self-contained logger jar.

## Run integration tests

Integration tests launch nested SBT with the exact, explicit JDK selected by each concrete JUnit class. The outer harness still runs on JDK 17.

Integration tests load the assembled logger jar into nested sbt with sbt's `apply -cp` command. \
Before running them, build the plugin jar:

`sbt +prepareIntegrationTestArtifacts`

This test-only preparation step copies the self-contained primary artifact to `target/integration-tests/artifacts/sbt-<binary-version>.jar`. It does not change the versioned filenames or target-directory layout used by normal packaging and publishing.

Then run the tests:

`sbt test`

### Integration test workflow

1. The pre-step assembles `sbt-teamcity-logger` and stages one self-contained JAR per SBT line under `target/integration-tests/artifacts/`.
2. `sbt test` enters the Scala/JUnit harness in the `integrationTests` project.
3. Each JUnit test copies its fixture under `target/integration-tests/work/<runtime>/`, renders its `sbt.version=@SBT_VERSION@` template with that runtime's concrete version, then downloads or reuses the current launcher for the selected SBT line under `target/integration-tests/sbt-launcher`.
4. The nested sbt command file first runs `apply -cp <logger jar> jetbrains.buildServer.sbtlogger.SbtTeamCityLogger`.
5. Fixture-root names express their minimum SBT version: `testdata/1.0` is the legacy SBT 1.0 corpus, `testdata/1.3+` is the Scala-2.13 modern SBT 1 corpus, `testdata/1.9+` is the JaCoCo extension, and `testdata/2.0+` is the SBT 2 corpus. Every fixture must contain exactly one `sbt.version=@SBT_VERSION@` property; the harness rejects missing, concrete, or duplicate values before launching SBT.
6. The harness compares nested sbt output with the source fixture's `output.txt` regexes and checks `excludes.txt` when present.

Useful targeted commands:

`sbt testSbt100Jdk8`

`sbt testSbt1LatestJdk8`

`sbt testSbt1LatestJdk17`

`sbt testSbt2LatestJdk17`

`sbt testAllSbtVersions`

The integration matrix is intentionally limited rather than a full SBT × JDK cross-product:

| JUnit class | Nested SBT | JDK | Fixture roots |
| --- | --- | --- | --- |
| `SbtLoggerOutputTest_1_0_0_Jdk8` | 1.0.0 | 8 | `1.0` |
| `SbtLoggerOutputTest_1_Latest_Jdk8` | 1.12.15 | 8 | `1.3+`, `1.9+` |
| `SbtLoggerOutputTest_1_Latest_Jdk17` | 1.12.15 | 17 | `1.3+`, `1.9+` |
| `SbtLoggerOutputTest_2_Latest_Jdk17` | 2.0.6 | 17 | `2.0+` |

This balance makes the legacy baseline, current SBT 1 on both supported JDKs, and current SBT 2 meaningful and visible while avoiding the runtime and maintenance cost of combinations that do not add useful compatibility evidence. Future JDK changes intentionally rename the affected concrete class and alias.

`testSbt100`, `testSbt1Latest`, `testSbt2Latest`, and `testSbt200` remain compatibility aliases; use the JDK-qualified aliases when selecting a concrete matrix entry. The JaCoCo scenario runs on the current SBT 1 classes through `1.9+` and on SBT 2 through `2.0+`; `publishTest` remains dormant.

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
6. Only after those checks pass, CI cross-publishes both logger variants with the same derived version. The CI publishing step must use cross publication (for example, `+publish`), not a single-target `publish` invocation.
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
