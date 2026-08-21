// SBT 2 no longer provides the legacy IntegrationTest configuration; recreate its `it` scope for this reproducer.
lazy val IntegrationTest = config("it").extend(Test)

lazy val root = (project in file("."))
  // Create the `it` configuration so the reproducer exercises IntegrationTest / testQuick, not Test / testQuick.
  .configs(IntegrationTest)
  .settings(inConfig(IntegrationTest)(Defaults.testSettings))
  .settings(
    libraryDependencies ++= Seq(
      "com.novocode" % "junit-interface" % "0.11" % "it",
      "org.junit.vintage" % "junit-vintage-engine" % "5.3.1" % "it"
    ),
    IntegrationTest / logBuffered := false,
    scalaVersion := "2.12.7",
    IntegrationTest / parallelExecution := false
  )
