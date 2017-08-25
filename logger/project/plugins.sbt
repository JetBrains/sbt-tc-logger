addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.1")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "2.0.0")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.5")

resolvers += Resolver.url(
  "bintray-sbt-plugin-releases",
  url("https://dl.bintray.com/content/sbt/sbt-plugin-releases"))(
  Resolver.ivyStylePatterns)
