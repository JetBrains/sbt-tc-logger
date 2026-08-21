---
name: start-local-teamcity-with-latest-runner-and-logger
description: Start or refresh the disposable local TeamCity SDK so it uses the current sbt-tc-logger checkout and sibling tc-sbt-runner checkout. Use when local TeamCity must exercise uncommitted logger or runner changes, including before manual SBT runner validation.
---

# Start Local TeamCity with Latest Runner and Logger

Use the runner's deployment scripts; do not copy their publish, plugin-replacement, or server-restart logic. The workflow publishes logger artifacts to Maven Local, replaces the SBT Runner ZIP only in the disposable SDK server, and starts its local agent.

## Preconditions

- Resolve the current checkout as the `sbt-tc-logger` repository and the sibling `tc-sbt-runner` repository. Allow the caller to supply a non-sibling runner path.
- Require macOS JDK 17, `sbt`, the runner's `mvnw`, and `publish-local-sbt-tc-logger-and-restart-teamcity.sh`.
- Require an already initialized local TeamCity SDK server. Its data directory and `WEB-INF/plugins/sbt-runner.zip` must exist before deployment.
- Treat `TC_SBT_SERVER_DIR`, `TC_SBT_URL`, `TC_SBT_START_TIMEOUT_SECONDS`, `TC_SBT_LOGGER_SBT_COMMAND`, and `TC_SBT_MAVEN_REPOSITORY_DIR` as optional caller overrides. Do not require a clean logger or runner worktree.
- Never point this workflow at a normal or production TeamCity installation. Do not use it as a first-time SDK bootstrap procedure.

## Deploy

Resolve the paths and JDK before starting work. Stop and report the missing prerequisite if any check fails.

```bash
LOGGER_REPOSITORY_DIR="$(git rev-parse --show-toplevel)"
RUNNER_REPOSITORY_DIR="${TC_SBT_RUNNER_REPOSITORY_DIR:-$LOGGER_REPOSITORY_DIR/../tc-sbt-runner}"
JDK17_HOME="${TC_SBT_JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
SERVER_DIR="${TC_SBT_SERVER_DIR:-/Users/dmitrii.naumenko/Library/Caches/TeamCity/tc-sbt-runner-2026.1/server}"
LOGGER_SBT_COMMAND="${TC_SBT_LOGGER_SBT_COMMAND:-sbt}"

test -x "$RUNNER_REPOSITORY_DIR/mvnw"
test -x "$RUNNER_REPOSITORY_DIR/scripts/publish-local-sbt-tc-logger-and-restart-teamcity.sh"
test -x "$JDK17_HOME/bin/java"
test -d "$SERVER_DIR/.datadir"
test -f "$SERVER_DIR/webapps/ROOT/WEB-INF/plugins/sbt-runner.zip"
command -v "$LOGGER_SBT_COMMAND"

JAVA_HOME="$JDK17_HOME" \
TC_SBT_LOGGER_REPOSITORY_DIR="$LOGGER_REPOSITORY_DIR" \
/usr/bin/env bash "$RUNNER_REPOSITORY_DIR/scripts/publish-local-sbt-tc-logger-and-restart-teamcity.sh"
```

Keep the deployment in this one command. The helper resolves the dirty-checkout dynver version once, publishes both SBT 1.x and SBT 2.x artifacts, passes that version to the runner build, preserves the original bundled ZIP, and waits for the local server to respond.

## Verify and diagnose

- Require a successful command exit and all of these output milestones: a resolved logger version, published SBT 1.x and SBT 2.x artifacts, local runner rebuild, and `TeamCity is ready at ...`.
- Treat the script's ready response as valid for HTTP 2xx, 3xx, or 401; an authenticated TeamCity root can correctly return 401 before browser login.
- Report the helper's exact failed preflight or build step. Do not manually copy plugin ZIPs, delete SDK files, or substitute a normal TeamCity instance.
- If the SDK data directory or bundled runner ZIP is absent, state that the local SDK must be initialized once through the runner's normal setup before rerunning this workflow.
- Do not queue a demo build, inspect feature behavior, or restore the bundled runner as part of this skill.
