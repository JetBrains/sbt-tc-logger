import sbt.Configurations.IntegrationTest

lazy val root = project.in(file("."))
  // Create the `it` configuration so the reproducer exercises `it:testQuick`, not Test / testQuick.
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(
    libraryDependencies ++= Seq(
      "junit" % "junit" % "4.13.2" % "it",
      "com.github.sbt" % "junit-interface" % "0.13.3" % "it"
    ),
    IntegrationTest / logBuffered := false,
    scalaVersion := "2.13.18",
    IntegrationTest / parallelExecution := false
  )
