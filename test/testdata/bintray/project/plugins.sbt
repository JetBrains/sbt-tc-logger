resolvers += Resolver.url("bintray-sbt-plugin-releases",url("https://dl.bintray.com/content/sbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns)

addSbtPlugin("org.jetbrains.teamcity.plugins" % "sbt-teamcity-logger" % "0.5.0")
