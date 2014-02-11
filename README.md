SBT TeamCity logger
=============

This plugin extends SBT standard output and add messages that TeamCity build server can use to improve build result presentation.

You don't need this plugin if you use [TeamCity SBT runner](https://github.com/JetBrains/tc-sbt-runner) with 'Auto' installation mode.

### Installation

Register SBT TeamCity logger as a plugin for your project or as a global plugin for your SBT installation according to [SBT documentation](http://www.scala-sbt.org/0.13.0/docs/Getting-Started/Using-Plugins)

`addSbtPlugin("org.jetbrains.teamcity.plugins" % "sbt-teamcity-logger" % '0.1.0-SNAPSHOT')`


### Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.