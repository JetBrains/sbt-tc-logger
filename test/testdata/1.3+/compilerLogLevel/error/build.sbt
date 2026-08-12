// TW-35404 - Verifies that error-level logging suppresses compiler debug noise.
ThisBuild / scalaVersion := "2.13.18"
logLevel := Level.Error
