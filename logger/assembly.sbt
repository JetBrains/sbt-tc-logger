import AssemblyKeys._

assemblySettings

jarName in assembly := "sbt-teamcity-logger-${build.number}.jar"

artifact in (Compile, assembly) ~= { art =>
  art.copy(`classifier` = Some("assembly"))
}

addArtifact(artifact in (Compile, assembly), assembly)