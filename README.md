
[![official JetBrains project](http://jb.gg/badges/official-plastic.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

SBT TeamCity logger
=============

This plugin extends SBT standard output with service messages that TeamCity build server uses to present build results.

You don't need this plugin if you use [TeamCity SBT runner](https://www.jetbrains.com/help/teamcity/simple-build-tool-scala.html) with 'Auto' installation mode.

### Installation

Add the following to your project/plugins.sbt file:

`resolvers += Resolver.url("jetbrains-teamcity-repository",url("https://download.jetbrains.com/teamcity-repository/"))(Resolver.ivyStylePatterns)`

`addSbtPlugin("org.jetbrains.teamcity.plugins.sbt" % "sbt-teamcity-logger" % "1.0")`

or register plugin as a global plugin for your SBT according to [SBT documentation](https://www.scala-sbt.org/1.x/docs/Plugins.html#Plugins)

Plugin is compatible with SBT version 1.x.


### Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.
