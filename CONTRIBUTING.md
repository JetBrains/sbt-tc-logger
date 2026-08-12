# Contributing to SBT TeamCity Logger

## Prerequisites

Use JDK 17 to run the repository's SBT build and integration-test harness; set `JAVA_HOME` to a JDK 17 installation before invoking `sbt`. \
The host remains SBT 1.12.12 while it cross-builds the logger against SBT 1.12.12/Scala 2.12.21 and SBT 2.0.0/Scala 3.8.4.

The SBT 1.x logger is compiled with Java 8 release compatibility so it remains loadable by supported SBT 1 runtimes; the SBT 2.x logger and the build itself run on JDK 17. \
Running the full integration suite also requires a local Java 8 or Java 11 installation for the legacy SBT fixtures.

## Build the logger locally

`sbt +publishLocal`

Use this as the main local-development build command. It publishes the compatibility variants to the local Maven repository using artifact filenames derived from their coordinates and version.

The logger sources live directly under `src/`. \
TeamCity service messages are resolved as the managed `org.jetbrains.teamcity:serviceMessages` dependency and packaged into the self-contained logger jar.

## Run integration tests

Integration tests launch the SBT 1.x and SBT 2.x runtimes. \
SBT 1 uses Java 8 or Java 11 discovered from standard OS locations; the SBT 2.0.4 fixtures run on Java 17.

Integration tests load the assembled logger jar into nested sbt with sbt's `apply -cp` command. \
Before running them, build the plugin jar:

`sbt +prepareIntegrationTestArtifacts`

This test-only preparation step copies the self-contained primary artifact to `target/integration-tests/artifacts/sbt-<binary-version>.jar`. It does not change the versioned filenames or target-directory layout used by normal packaging and publishing.

Then run the tests:

`sbt test`

### Integration test workflow

1. The pre-step assembles `sbt-teamcity-logger` and stages one self-contained JAR per SBT line under `target/integration-tests/artifacts/`.
2. `sbt test` enters the Scala/JUnit harness in the `integrationTests` project.
3. Each JUnit test copies its fixture under `target/integration-tests/work/<runtime>/`, then downloads or reuses the runtime launcher version under `target/integration-tests/sbt-launcher`.
4. The nested sbt command file first runs `apply -cp <logger jar> jetbrains.buildServer.sbtlogger.SbtTeamCityLogger`.
5. The fixture's own `project/build.properties` still decides which sbt runtime the launcher boots.
6. The harness compares nested sbt output with the source fixture's `output.txt` regexes and checks `excludes.txt` when present.

Useful targeted commands:

`sbt testSbt100`

`sbt testSbt200`

## TeamCity SBT Runner

The [TeamCity SBT Runner repository](https://github.com/JetBrains/tc-sbt-runner) is the logger's intended consumer. \
It is JetBrains-private, but documents and implements the runner integration that selects the compatible logger artifact and embeds it into TeamCity distributions.

## Artifact compatibility

Releases are versioned by Git tags through `sbt-dynver`, for example `v1.1.0`. \
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
`L` and `N` are release-time tag versions, not snapshots or source revisions. \
After `N` is published and used by TeamCity to build this repository, upgrade this repository's host build to SBT 2.0.4.

The primary published jar is self-contained.
”