import AssemblyKeys._

assemblySettings

jarName in assembly := "sbt-teamcity-logger.jar"

excludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
  cp filter {_.data.getName != "common-api-8.0.jar"}
}

artifact in (Compile, assembly) ~= { art =>
  art.copy(`classifier` = Some("assembly"))
}

addArtifact(artifact in (Compile, assembly), assembly)