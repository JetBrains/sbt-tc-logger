package org.jetbrains.teamcity.plugins.sbt.logger.utils

import java.io.File

private[logger] object IntegrationTestLayout {
  /**
   * Discovers the repository root used by the logger integration-test harness.
   *
   * The outer JUnit suite is an sbt subproject rooted at `integration-tests/`.<br>
   * When sbt forks the test JVM, the process working directory is that subproject directory, not the repository root.<br>
   * The harness still needs the repository root because it reads immutable fixtures from `integration-tests/testData`,
   * loads already-packaged plugin jars from root `target`, reads root `project/build.properties` to pick a launcher,
   * and keeps nested-sbt caches under root `target/integration-tests`.
   */
  def repoRoot(): File = {
    val currentWordingDir = new File(".").getAbsoluteFile

    val fileWithParents = LazyList.iterate(currentWordingDir)(_.getParentFile).takeWhile(_ != null)
    val found = fileWithParents.find(isRepositoryRoot)
    found.getOrElse {
      throw new IllegalStateException("Could not discover repository root from " + new File(".").getAbsolutePath)
    }
  }


  private def isRepositoryRoot(candidate: File): Boolean =
    new File(candidate, "project/build.properties").isFile &&
      new File(candidate, "src/main").isDirectory &&
      new File(candidate, "integration-tests/testData").isDirectory
}
