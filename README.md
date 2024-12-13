
[![official JetBrains project](http://jb.gg/badges/official-plastic.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

SBT TeamCity logger
=============

This plugin extends SBT standard output with service messages that TeamCity build server uses to present build results.

You don't need this plugin if you use [TeamCity SBT runner](http://confluence.jetbrains.com/display/TW/SBT+Runner+Plugin) with 'Auto' installation mode.

### Installation

Add the following to your project/plugins.sbt file:

`resolvers += Resolver.url("bintray-sbt-plugin-releases",url("https://dl.bintray.com/content/sbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns)`

`addSbtPlugin("org.jetbrains.teamcity.plugins" % "sbt-teamcity-logger" % "0.3.0")`

or register plugin as a global plugin for your SBT according to [SBT documentation](http://www.scala-sbt.org/0.13.0/docs/Getting-Started/Using-Plugins)

Plugin is compatible with SBT version 0.13.x and 1.x


### Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.
