addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.10.1")

resolvers += "JetBrains Maven Repository" at "http://repository.jetbrains.com/all"

libraryDependencies ++= Seq(
  ("org.jetbrains.teamcity" % "common-api" % "8.0")
)


