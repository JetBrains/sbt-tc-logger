resolvers += "SonaType" at "https://oss.sonatype.org/content/groups/public"

// Scala 2.10's compiler bridge cannot run on the Java 17 runtime required by SBT 2.
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.9" % Test

Test / logBuffered := false

scalaVersion := "2.12.20"

Test / parallelExecution := false
