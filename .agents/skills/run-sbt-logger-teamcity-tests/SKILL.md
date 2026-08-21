---
name: run-sbt-logger-teamcity-tests
description: "Push an SBT Logger source branch and monitor its automatic TeamCity Tests Aggregator chain to completion. Use when asked to run this repository's tests through TeamCity, report the aggregate build and bucket results, and ensure no tag or Publish build is triggered."
---

# SBT Logger TeamCity Tests

Run the repository's VCS-triggered test matrix through the **Tests Aggregator**:

`https://buildserver.labs.intellij.net/buildConfiguration/TC_Plugins_SbtRunner_SbtLogger_TestsAggregator`

## Workflow

1. Inspect the current branch, remote, and worktree. State that uncommitted changes will remain local.

2. Push only the branch with an explicit heads refspec. Never use `--follow-tags`, push tags, or push a broad refspec.

   ```bash
   git push --porcelain origin "refs/heads/<branch>:refs/heads/<branch>"
   ```

3. Wait for TeamCity to discover the VCS change. Use the `teamcity` CLI when authentication works; otherwise use an existing signed-in browser session. Filter the aggregator page to the pushed branch and identify the newly triggered build rather than starting a manual build.

4. Monitor until the aggregate build reaches a terminal state. Record its number, branch, start time, duration, aggregate status, and each bucket outcome. Treat a child build entering `running` as evidence that the TeamCity test workflow was invoked, but report failures, cancellations, and timeouts exactly as TeamCity shows them.

5. Report the direct aggregator-build link. Do not start or trigger **Publish**, even when the test chain succeeds. Do not alter TeamCity settings unless that is explicitly requested.

## Current Matrix

Expect five independent test buckets plus the shared build-number generator:

- SBT 1.4 / JDK 8
- SBT 1.12 / JDK 8
- SBT 1.12 / JDK 17, including detailed Coursier reporting
- SBT 2.0 / JDK 17, including detailed Coursier reporting
- Other integration tests, excluding every class covered by the four runtime buckets
