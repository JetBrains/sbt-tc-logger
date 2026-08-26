// Keep this ordinary-compilation fixture aligned with the other SBT 2 compilation scenarios.
ThisBuild / scalaVersion := "2.12.20"

lazy val backend = project.in(file("backend"))

lazy val root = project.in(file(".")).aggregate(backend)

