import sbt.Configurations.IntegrationTest

lazy val root = (project in file("."))
  // Create the `it` configuration so the reproducer exercises `it:testQuick`, not Test / testQuick.
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.novocode" % "junit-interface" % "0.11" % "it",
      "org.junit.vintage" % "junit-vintage-engine" % "5.3.1" % "it"
    ),
    logBuffered in IntegrationTest := false,
    scalaVersion := "2.12.7",
    parallelExecution in IntegrationTest := false
  )
