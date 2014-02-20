SBT TeamCity logger
=============

This plugin extends SBT standard output with service messages that TeamCity build server uses to present build results.

You don't need this plugin if you use [TeamCity SBT runner](http://confluence.jetbrains.com/display/TW/SBT+Runner+Plugin) with 'Auto' installation mode.

### Installation

Register SBT TeamCity logger as a plugin for your project or as a global plugin for your SBT installation according to [SBT documentation](http://www.scala-sbt.org/0.13.0/docs/Getting-Started/Using-Plugins).

    resolvers += "Artifactory Realm" at "http://repository.jetbrains.com/teamcity/"

    addSbtPlugin("org.jetbrains.teamcity.plugins" % "sbt-teamcity-logger" % "0.1.0-SNAPSHOT")

We support SBT version 0.13.x.


### Using

This plugin starts to work for builds running on TeamCity automatically and doesn't affect SBT output for other cases.
To be sure that plugin was installed correctly you can use `sbt-teamcity-logger`. Plugin status will be displayed.
