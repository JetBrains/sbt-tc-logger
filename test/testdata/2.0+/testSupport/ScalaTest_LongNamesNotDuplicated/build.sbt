// TW-46964 - Verifies that long ScalaTest names are not duplicated in reported events.
// Scala 2.10's compiler bridge cannot run on the Java 17 runtime required by SBT 2.
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.9" % Test

scalaVersion := "2.12.20"
