
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

Plugin is compatible with SBT version 1.x.


## Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.

## Development

Use the repository root as the sbt build. The root project is the `logger` sbt plugin project, runs on sbt 1.12.12, and builds the logger plugin for sbt 1.x.

`sbt projects`

`sbt "project logger" "+publishLocal"`

The logger sources live directly under `src/`. 
TeamCity service messages are resolved as the managed `org.jetbrains.teamcity:serviceMessages` dependency and packaged into the self-contained logger jar.

Releases are versioned by Git tags through `sbt-dynver`, for example `v1.1.0`. The public Maven artifacts use the logger release version as the artifact version and put the Scala/sbt binary versions in the artifact id:

- `org.jetbrains.teamcity.plugins.sbt:sbt-teamcity-logger_2.12_1.0:<logger version>`

The primary published jar is self-contained.

## Integration tests
Integration tests launch old sbt runtimes. The harness automatically discovers a Java 8 or Java 11 installation from
standard OS locations for nested sbt 1.0 processes.

Integration tests load the assembled logger jar into nested sbt with sbt's `apply -cp` command. Before running them,
build the plugin jar:

`sbt +packageBin`

Then run the harness:

`sbt test`

### Integration test workflow:
1. The pre-step assembles `sbt-teamcity-logger` for sbt 1.x under `target/scala-*/sbt-*`.
2. `sbt test` enters the Scala/JUnit harness in the `integrationTests` project.
3. Each JUnit test copies its fixture under `target/integration-tests/work/<runtime>/`, then downloads or reuses the recent launcher version from root `project/build.properties` under `target/integration-tests/sbt-launcher`.
4. The nested sbt command file first runs `apply -cp <logger jar> jetbrains.buildServer.sbtlogger.SbtTeamCityLogger`.
5. The fixture's own `project/build.properties` still decides which sbt runtime the launcher boots.
6. The harness compares nested sbt output with the source fixture's `output.txt` regexes and checks `excludes.txt` when present.

Useful targeted commands:
`sbt testSmoke`
`sbt testSbt100`
