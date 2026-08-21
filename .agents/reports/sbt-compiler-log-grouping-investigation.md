# SBT compiler-log grouping investigation

## Goal

Put normal compiler output (including `info`, and `debug`/`trace` when SBT emits it)
inside the TeamCity `Scala compiler [...]` node, while retaining structured compiler
warnings and errors for TeamCity inspections.  The solution must not infer compiler
lifecycle from log text.

## Finding

Decorating the original `compile` task is the only clean and reliable SBT-level
mechanism for this requirement.  It must be done for both `Compile / compile` and
`Test / compile` (with the equivalent SBT 1.12 scoped syntax).

The desired shape is:

```scala
(Compile / compile).toSettingKey ~= { original =>
  original
    .dependsOn(sbt.std.TaskExtra.task(compilationStarted(...)))
    .andFinally(compilationFinished(...))
}
```

`dependsOn` makes `compilationStarted` a prerequisite of the *original* compile
task, before any of its task-graph dependencies and their logs execute.
`andFinally` closes the flow after that task succeeds or fails, without changing
the task result.

## Why the current wrapper is too late

The current lifecycle wrappers assign `compile` in terms of
`(compile).result.value`.  In SBT, `.value` contributes a dependency to the task
graph.  Therefore the original compile task, including its initial
`[info] compiling ...` output, runs before the wrapper body calls
`compilationBlockStart`.

The log router already identifies `compile` and `compileIncremental` as compiler
tasks and sends them to `TCLogAppender.logCompilerTask`.  That method emits an
event under the compiler flow only while `activeCompilationFlows` contains that
flow ID; otherwise it emits an ungrouped message.  The observed flat `info` lines
are consequently a lifecycle-ordering issue, not an inability to classify the
messages.

Relevant code:

- `src/main/scala-sbt-2.0/jetbrains/buildServer/sbtlogger/SbtTeamCityLogger.scala`
- `src/main/scala-sbt-1.0/jetbrains/buildServer/sbtlogger/SbtTeamCityLogger.scala`
- `src/main/scala/jetbrains/buildServer/sbtlogger/TCLogAppender.scala`

## Alternatives considered

### Compiler reporter only

`xsbti.Reporter` is the correct typed source for warnings and errors and remains
necessary for inspections.  It does not receive regular task logger messages such
as `[info] compiling ...`, and it is not a complete compile start/finish lifecycle
source.  It cannot satisfy the goal alone.

### LogManager or appender callbacks only

`LogManager` receives the `ScopedKey` when it creates a logger, allowing the plugin
to classify compiler task events.  It does not expose a corresponding reliable task
completion callback to appenders.  Starting a compiler node on its first log event
would still leave completion to a heuristic or an unrelated callback, which is not
safe for nested or parallel work.

### Wrap `compileIncremental` only

This is narrower but does not provide the semantic boundary required by the feature:
logs associated with the outer `compile` task or custom compile composition can be
missed.  Decorating the public `compile` task captures its full normal execution
while the existing logger routing keeps both `compile` and `compileIncremental`
events in the same flow.

### Parse console text

Matching strings such as `compiling`, `done compiling`, or compilation-failure
summaries is brittle, version-sensitive, and can create false positives.  It should
not be used.

### Command/build-completion hooks

These are too broad: they do not represent a per-module/per-configuration compiler
lifecycle and cannot reliably distinguish concurrent or nested compilation work.

## Expected output

For a normal compilation:

```text
Scala compiler [module]
  [info] compiling 1 Scala source to ...
  [debug] ...                 # only when enabled and emitted by SBT
  [info] done compiling
  [warn] ...                  # also reported as a TeamCity inspection
```

Compiler errors remain structured inspection messages inside the same flow.  Late
messages from separate task flows should remain ungrouped; reopening a compiler
flow from their text would reintroduce the duplicate-node problem.

## Scope and caveats

- The fix reliably covers normal logs emitted through `compile` and
  `compileIncremental`, including enabled debug/trace log events.
- SBT does not expose causal task ancestry to the logger.  A message emitted by an
  unrelated setup or cleanup task merely because it happens during compilation
  cannot be grouped as compiler output cleanly; expanding to those messages would
  require broad task-name matching or timing heuristics.
- `dependsOn(...).andFinally(...)` is standard task composition.  The proposed
  task constructor, `sbt.std.TaskExtra.task`, is an SBT implementation utility
  rather than a strongly stable public API; this project already uses it for the
  dependency-resolution lifecycle wrappers.  This is a small compatibility
  dependency, not a text-based workaround.
- As with any SBT setting transformation, a later plugin that replaces the same
  `compile` setting can supersede the wrapper.  That is normal SBT setting
  precedence behavior, not a logger-specific race.

## Conclusion

There is no leaner supported callback that supplies both a pre-log compiler start
and a reliable post-task finish.  Task transformation is the correct architectural
layer.  It is cleaner than the current self-referential wrapper and avoids all
message-content heuristics.
