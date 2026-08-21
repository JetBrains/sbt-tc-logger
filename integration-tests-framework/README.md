These test utilities are copied and adapted from the sbt-structure integration-test utils:

[JetBrains/sbt-structure extractor test utils](https://github.com/JetBrains/sbt-structure/tree/master/extractor/src/test/scala/org/jetbrains/sbt/integrationTests/utils)

The copied code solves common integration-test problems around sbt process execution,
version handling, filesystem helpers, and Java discovery. For now the code lives here
so the logger tests can reuse the proven implementation shape without adding a new
dependency between repositories.

Longer term, this code should probably be extracted to a common JetBrains test utility
library, or moved closer to shared sbt/tooling infrastructure if that becomes a better
home.
