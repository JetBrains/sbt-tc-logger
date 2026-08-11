
[![official JetBrains project](http://jb.gg/badges/official-plastic.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

SBT TeamCity logger
=============

This plugin extends SBT standard output with service messages that TeamCity build server uses to present build results.

You don't need this plugin if you use [TeamCity SBT runner](https://www.jetbrains.com/help/teamcity/simple-build-tool-scala.html) with 'Auto' installation mode.

## Installation

Add the following to your project/plugins.sbt file:

`resolvers += "jetbrains-teamcity-repository" at "<TeamCity Maven repository URL>"`

`addSbtPlugin("org.jetbrains.teamcity.plugins.sbt" % "sbt-teamcity-logger" % "<logger version>")`

or register plugin as a global plugin for your SBT according to [SBT documentation](https://www.scala-sbt.org/1.x/docs/Plugins.html#Plugins)

New logger releases support SBT 1.x and SBT 2.x. \
SBT 1 uses the standard `addSbtPlugin` installation shown above. \
SBT 2 uses the self-contained direct-load jar:
`apply -cp <path-to>/sbt-teamcity-logger_3_2.0-<logger version>.jar jetbrains.buildServer.sbtlogger.SbtTeamCityLogger`

The TeamCity SBT runner performs that direct load automatically in `Auto` installation mode.

SBT 0.13 is supported by the runner through the immutable final legacy release `L`
(`sbt-teamcity-logger_2.10_0.13:L`). New logger releases do not publish an SBT 0.13 artifact;
upgrade standalone SBT 0.13 installations to the pinned `L` release, or upgrade the build to SBT 1 or SBT 2.


## Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.

## Development

Use JDK 17 to run the repository's SBT build and integration-test harness; set `JAVA_HOME` to a JDK 17 installation
before invoking `sbt`. The host remains SBT 1.12.12 while it cross-builds the logger against SBT 1.12.12/Scala 2.12.21 and
SBT 2.0.0/Scala 3.8.4.

The SBT 1.x logger is compiled with Java 8 release compatibility so it remains loadable by supported SBT 1 runtimes;
the SBT 2.x logger and the build itself run on JDK 17. Running the full integration suite also requires a local
Java 8 or Java 11 installation for the legacy SBT fixtures.

TODO: after this SBT-2-capable logger release is published, upgrade the host build to SBT 2.0.4. TeamCity currently
builds and publishes this repository using the preceding logger release, which cannot run on SBT 2.

`sbt projects`

`sbt "project logger" "+publishLocal"`

The logger sources live directly under `src/`. 
TeamCity service messages are resolved as the managed `org.jetbrains.teamcity:serviceMessages` dependency and packaged into the self-contained logger jar.

Releases are versioned by Git tags through `sbt-dynver`, for example `v1.1.0`. The public Maven artifacts use the logger release version as the artifact version and put the Scala/sbt binary versions in the artifact id:

- `org.jetbrains.teamcity.plugins.sbt:sbt-teamcity-logger_2.12_1.0:<logger version>` for SBT 1.x
- `org.jetbrains.teamcity.plugins.sbt:sbt-teamcity-logger_3_2.0:<logger version>` for SBT 2.x

The SBT 2 artifact is deliberately a direct-load jar, rather than an SBT plugin-discovery artifact, so its
Maven coordinate remains the stable runner contract above. Load it with `apply -cp` as shown in Installation.
`publishLocal` for the next major release must produce only these two artifacts; it must not produce
`sbt-teamcity-logger_2.10_0.13`.

### Release compatibility

Before ending new SBT 0.13 logger production, tag and publish the final SBT-0.13-capable release `L` after its full
SBT 0.13 suite has passed. `L` is immutable: the TeamCity SBT runner must use its
`sbt-teamcity-logger_2.10_0.13:L` artifact for SBT 0.13 rather than a later logger release.

The next major release `N` publishes only the SBT 1 and SBT 2 artifacts listed above. `L` and `N` are release-time
tag versions, not snapshots or source revisions. After `N` is published and used by TeamCity to build this repository,
upgrade this repository's host build to SBT 2.0.4 as described in Development.

The primary published jar is self-contained.

## Integration tests
Integration tests launch the SBT 1.x and SBT 2.x runtimes. SBT 1 uses Java 8 or Java 11 discovered from standard OS
locations; the SBT 2.0.4 fixtures run on Java 17.

Integration tests load the assembled logger jar into nested sbt with sbt's `apply -cp` command. Before running them,
build the plugin jar:

`sbt +packageBin`

Then run the harness:

`sbt test`

### Integration test workflow:
1. The pre-step assembles `sbt-teamcity-logger` for the selected SBT line under `target/scala-*/sbt-*`.
2. `sbt test` enters the Scala/JUnit harness in the `integrationTests` project.
3. Each JUnit test copies its fixture under `target/integration-tests/work/<runtime>/`, then downloads or reuses the runtime launcher version under `target/integration-tests/sbt-launcher`.
4. The nested sbt command file first runs `apply -cp <logger jar> jetbrains.buildServer.sbtlogger.SbtTeamCityLogger`.
5. The fixture's own `project/build.properties` still decides which sbt runtime the launcher boots.
6. The harness compares nested sbt output with the source fixture's `output.txt` regexes and checks `excludes.txt` when present.

Useful targeted commands:
`sbt testSmoke`
`sbt testSbt100`
`sbt testSbt200`
