# SBT 2 legacy JUnit aggregate / `testFull` investigation

Date: 2026-08-20
Worktree: `/Users/dmitrii.naumenko/Desktop/dev/sbt-tc-logger-test-output-controls`
Branch: `codex/sbt2-junit-aggregate-investigation`
Evidence root: `target/investigation/sbt2-junit-aggregate/`

## Executive answer

The caveat that “the SBT 2 legacy JUnit aggregate demo discovered no tests” referred to one observed result in the reused disposable TeamCity checkout: the SBT 2.0.6 `junitRoot` aggregate compiled the two Java JUnit 4 suites, emitted no test events, and returned success. The affected modules use JUnit 4.13.2 with `junit-interface` 0.13.3.

It is the same observation that was later called an “SBT 2 legacy `junitRoot/testFull` test-discovery limitation,” but **the word “limitation” and the attribution to `testFull` are wrong**.

The corrected conclusion is:

- `junitRoot/testFull` is a public, aggregating SBT 2 task and works in a clean workspace.
- The failure already appeared with `junitRoot/test` before the TeamCity configuration was changed to `testFull`; `testFull` did not introduce it.
- The logger, `TCReportListener`, runner, result logger, JUnit adapter, and aggregate task are not the cause.
- The reused checkout contained an inconsistent Java compilation state: JUnit `.class` files remained under each child project's `test-classes`, while the corresponding Zinc `test-zinc/inc_compile.zip` analysis was missing or contained no generated-class/API data.
- Zinc recompilation over the pre-existing class paths then created another product-free analysis. SBT's `definedTests` consequently received no test classes and returned an empty set.
- Regenerating **both** `test-classes` and `test-zinc` repairs discovery. For this demo, the smallest robust fix is to replace the unscoped `clean ; cleanFiles` prefix with the aggregate-aware `junitRoot/clean`; external `target/out` removal is a broader fallback.

## Confidence

| Conclusion | Confidence | Basis |
|---|---:|---|
| This is not a `testFull` or aggregation limitation | Very high (99%) | Task metadata/source plus clean aggregate executions with and without every relevant logger artifact |
| Production TeamCity logger/runner/listener code is not the cause | Very high (99%) | Same stale checkout fails with no logger; clean checkouts pass with no logger, current logger, historical `22e444c9`, and deployed `478e4704` |
| Immediate mechanism is the inconsistent `test-classes` / `test-zinc` pair | Very high (99%) | Selective cleanup matrix and same-path good-to-bad controlled reproduction |
| Zinc's Java generated-class path-difference algorithm explains the product-free analysis | Very high (98%) | Zinc 2.0.4 source exactly matches the observed transition and artifact contents |
| Retaining `target/out` while changing/losing its CAS-backed analysis caused the historical TeamCity state | High (90%) | TeamCity preserves `target`; analyses are symlinks into disposable/cache-specific CAS locations; history and controlled reproduction match |
| The exact operation that first removed or invalidated the good analysis link between builds 1404 and 1406 | Medium (65%) | The original post-1404 filesystem snapshot no longer exists, so the precise deletion/invalidating event cannot be reconstructed |

“Truncated/corrupt analysis” should be refined to **semantically incomplete but readable Zinc analysis**. The small archives are valid gzip data and retain setup/source data; they lack products, generated class names, APIs, methods, and `org.junit.Test`. There is no evidence of byte-level truncation.

## What `junitRoot/testFull` actually is

SBT 2.0.6 defines:

- `testFull` as a public `taskKey[TestResult]("Executes all tests.")` in the downloaded `Keys.scala` at line 387;
- `testFull` as an uncached call to `executeTests`, followed by the resolved `TestResultLogger`, in `Defaults.scala` lines 1229-1238;
- `test` as `testQuick.evaluated` in `Defaults.scala` line 1251.

Runtime inspection confirms:

- `junitRoot / Test / testFull / aggregate = true`;
- both `module8JUnitSuccess / Test / testFull` and `module9JUnitFailures / Test / testFull` are related aggregate tasks;
- the task description is “Executes all tests.”

Evidence:

- `inspect-task-metadata.log`
- `inspect-aggregation-settings.log`
- `sbt-2.0.6-sources/sbt/Keys.scala`
- `sbt-2.0.6-sources/sbt/Defaults.scala`

This matters because SBT 2 changed plain `test` to incremental `testQuick` behavior and introduced `testFull` for unconditional execution. It did not make `testFull` internal or non-aggregating.

## Clean execution matrix

All of these clean SBT 2.0.6/JDK 17 aggregate executions ran six JUnit tests (three passing-suite methods and three failing-suite methods) and returned the expected nonzero exit because two tests intentionally fail:

| Environment | Result | Evidence |
|---|---|---|
| No TeamCity logger | Six tests; exit 1 | `no-logger-junitroot-testfull.log` |
| Current worktree logger | Six structured events; exit 1 | `logger-junitroot-testfull.log` |
| Historical logger from commit `22e444c9` (the version used around build 1608) | Six structured events; exit 1 | `logger-22e-runtime-set-junitroot-testfull.log` |
| Currently deployed logger `478e4704` | Six structured events; exit 1 | `deployed-478e-runtime-set-junitroot-testfull.log` |
| Runtime `set Global / localCacheDirectory`, `clean`, `cleanFiles`, and `clearCaches` | Six tests; exit 1 on clean state | logs above and `controlled-good.log` |

The clean logger transcript contains six `testStarted`/`testFinished` pairs and ends with `sbt.TestsFailedException`, demonstrating both discovery and aggregate failure semantics.

## Historical TeamCity timeline

| Build | SBT 2 task | Result | Interpretation |
|---:|---|---|---|
| 1404 | `junitRoot/test` | Compiled and ran six tests, process exit 0 | Discovery worked. Exit 0 was the separate historical no-op result-logger defect. |
| 1406 | `junitRoot/test` | Compiled both Java sources, ran no tests, exit 0 | The discovery state was already broken before `testFull` was adopted. |
| 1408 | `junitRoot/test` | Compiled, explicitly reported zero/no tests, exit 0 | `preserveConsole=true` changed visibility, not discovery. |
| 1508 | `junitRoot/testFull` | Compiled, no test events, exit 0 | Same retained state with a different public task. |
| 1608 | `junitRoot/testFull` | Compiled, no test events, exit 0 | Same retained state with historical logger `22e444c9`. |
| 1701 | `junitRoot/testFull` | Compiled, no test events, exit 0 | Reproduced with deployed logger `478e4704`. |

Evidence:

- `teamcity-build-1404.log`
- `teamcity-build-1406.log`
- `teamcity-build-1408.log`
- `teamcity-build-1508.log`
- `teamcity-build-1608.log`
- `teamcity-build-1701.log`

The TeamCity preparation step uses `rsync --delete` but explicitly excludes `target`, so the same SBT 2 output tree survives source refreshes. Build 1406 also switched the surrounding boot/global/Coursier/Ivy/tmp caches to fresh build-specific locations. Each SBT 2 command additionally sets `Global / localCacheDirectory` to a new temporary directory. SBT 2 represents `test-zinc/inc_compile.zip` as a symlink into that content-addressed cache, while `test-classes` remains inside the retained `target/out` tree. This makes the retained output dependent on the continued availability and consistency of a separate CAS location.

The demo's current `root` project aggregates modules 1-7 only. `junitRoot` separately aggregates modules 8-9. The historical prefix `clean ; cleanFiles ; clearCaches` therefore did not clean the JUnit projects:

- unscoped `clean` ran for `root` and modules 1-7;
- invoking `cleanFiles` merely evaluated the task that supplies extra paths to `clean`; it did not delete those paths by itself;
- `clearCaches` resets compiler/classloader/in-memory cache state but does not remove these build outputs.

This explains why the bad JUnit output state persisted across later TeamCity runs once it existed.

The history proves that retained output plus missing/incomplete analysis existed by build 1406. It does not preserve enough filesystem state to prove which exact cleanup, cache switch, or temporary-directory lifecycle event first invalidated the previously good analysis link.

## Stale-checkout isolation

The same TeamCity checkout skips tests even when the logger is completely absent:

- `original-teamcity-workspace-no-logger-fresh-cache.log`: no test execution, exit 0.

Copying that checkout including `target` reproduces the skip elsewhere:

- `stale-copy-no-logger-fresh-cache.log`: no test execution, exit 0.

Removing only `target/out/value` does not repair it:

- `stale-copy-without-out-value.log`: no test execution, exit 0.

Regenerating the complete `target/out` does repair it:

- `stale-copy-without-out.log`: six tests, expected `TestsFailedException`, exit 1.

This establishes that the anomaly follows retained build output, not the TeamCity process, the checkout path, logger installation, or action-cache directory alone.

## Minimal cleanup matrix

Starting from copies of the reproduced bad tree, both JUnit child modules were changed symmetrically:

| Removed/regenerated state | Result | Evidence |
|---|---|---|
| Nothing (bad baseline) | No tests, exit 0 | `stale-copy-no-logger-fresh-cache.log` |
| `definedTestNames/data` only | No tests, exit 0 | `matrix2-remove-defined.log` |
| All child test streams only | No tests, exit 0 | `matrix2-remove-test-streams.log` |
| `test-zinc` only | Java recompiles; no tests; new product-free analysis; exit 0 | `matrix2-remove-test-zinc.log` |
| `test-classes` only | No compile and no tests; exit 0 | `matrix3-remove-test-classes.log` |
| `test-classes` plus test streams, retaining bad `test-zinc` | No tests, exit 0 | `matrix3-remove-test-classes-and-streams.log` |
| test streams plus `test-zinc`, retaining class files | Java recompiles; no tests; exit 0 | `matrix2-remove-child-streams-and-zinc.log` |
| **`test-classes` plus `test-zinc`** | **Six tests; expected failure; exit 1** | `matrix3-remove-test-classes-and-zinc.log` |
| Entire two child target directories | Six tests; expected failure; exit 1 | `matrix3-keep-only-shared.log` |
| Entire `target/out` | Six tests; expected failure; exit 1 | `stale-copy-without-out.log`, `controlled-good.log` |
| Unscoped `clean`, then `junitRoot/testFull` | Cleans only `root` and modules 1-7; JUnit state remains bad; no tests; exit 0 | `clean-scope-unscoped.log` |
| `all clean`, then `junitRoot/testFull` | Same current-root aggregation scope; no tests; exit 0 | `clean-scope-all.log` |
| **`junitRoot/clean`, then `junitRoot/testFull`** | **Aggregately cleans `junitRoot` and modules 8-9; six tests; expected failure; exit 1** | `clean-scope-junitroot.log` |
| Exact recommended command, independently rerun on another bad-tree copy | Six tests; two intentional failures; `TestsFailedException`; exit 1 | `handover-scoped-clean.log` |
| Explicit `module8JUnitSuccess/clean ; module9JUnitFailures/clean`, then aggregate test | Six tests; expected failure; exit 1 | `clean-scope-explicit-children.log` |

`definedTestNames` is therefore a downstream symptom, not the corrupting cache. Removing only the analysis is also insufficient because recompilation over the retained class paths recreates an empty analysis.

## Why the historical `clean ; cleanFiles` prefix did not clean JUnit

Runtime debug logging on a copied bad workspace gives exact scope evidence:

- `clean-scope-unscoped.log` contains clean deletions for `root`, `module1success` through `module7testerrors`, and no clean deletion for `junitroot`, `module8junitsuccess`, or `module9junitfailures`.
- The subsequent `junitRoot/testFull` still exits 0 with no tests.
- `clean-scope-unscoped-files-before.txt` and `clean-scope-unscoped-files-after.txt` show that the child JUnit class/analysis pair survived that clean (the test invocation subsequently rewrote another product-free analysis).
- `all clean` behaves the same because `all` changes failure/parallel command execution behavior; it does not discover unrelated projects outside the current project's aggregation graph.

The project graph in `build.sbt` is decisive:

```scala
lazy val junitRoot = project.aggregate(module8JUnitSuccess, module9JUnitFailures)
lazy val root = project.aggregate(
  module1Success,
  module2Warnings,
  module3Warnings,
  module4Errors,
  module5Errors,
  module6Downstream,
  module7TestErrors
)
```

### `cleanFiles` semantics

In SBT 2.0.6:

- `Keys.scala:202` defines `cleanFiles` as `taskKey[Seq[File]]("The files to recursively delete during a clean.")`.
- `Defaults.scala:1988` makes its default result an empty vector.
- `Clean.scala:111-115` shows that the **`clean` task** evaluates `cleanFiles` and deletes each returned path.
- Invoking the `cleanFiles` task alone only computes/returns its sequence.

The runtime probe made this observable with a nonempty custom value:

1. `set cleanFiles := Seq(file("cleanfiles-probe.txt")) ; show cleanFiles ; cleanFiles` listed the path and returned success, while the file still existed (`cleanfiles-runtime-list-only.log`, `cleanfiles-runtime-list-only-state.txt`).
2. A later `set cleanFiles := Seq(file("cleanfiles-probe.txt")) ; clean` deleted the file (`cleanfiles-runtime-consumed-by-clean.log`, `cleanfiles-runtime-consumed-by-clean-state.txt`).

Thus `cleanFiles` is not a second cleaning command. Appending `; cleanFiles` after `clean` adds no deletion.

### `ThisBuild` is a scope, not “every project”

`ThisBuild / ...` selects the build axis for one scoped key. It does not iterate project scopes. SBT 2.0.6 makes this explicit in `Aggregation.scala` lines 247-251: build-level references (`ThisBuild` and `BuildRef`) do not aggregate; only project-level references participate in project aggregation.

Static inspection and runtime probes on copies of the bad tree show:

| Expression | Resolution/runtime behavior | Evidence |
|---|---|---|
| `ThisBuild / clean` | Resolves through delegation to `Global / clean`, whose default in `Defaults.scala:159` is a no-op. It executes one task, deletes no output, leaves discovery empty, and exits 0. | `handover-thisbuild-inspect.log`, `handover-thisbuild-clean.log` |
| `ThisBuild / cleanFiles` | Has no build/global-scoped definition. Direct invocation reports `No such setting/task` and exits 1; it neither queries nor runs each project's `cleanFiles`. | `handover-thisbuild-inspect.log`, `handover-thisbuild-cleanfiles.log` |
| `ThisBuild / cleanKeepFiles`, `ThisBuild / cleanKeepGlobs` | Delegate to global setting defaults. They configure exclusions used by a real clean; they are not cleaning tasks. | `handover-thisbuild-inspect.log` |
| `ThisBuild / cleanFull` | Is invalid because `cleanFull` is an SBT command, not a scoped key. | `handover-thisbuild-cleanfull-syntax.log` |

For this build's two disconnected aggregation roots, `root/clean ; junitRoot/clean` executes project-scoped clean semantics across every currently defined project. Debug output covers `root`, modules 1-7, `junitRoot`, and modules 8-9; the following `junitRoot/testFull` runs all six tests and exits 1 as expected (`handover-both-roots-clean.log`). This is explicit graph coverage, not a special all-project scope.

SBT 2 also provides the unscoped `cleanFull` command. `Clean.scala` lines 217-232 show that it clears disk action caches, runs the local root's aggregated clean, deletes the build's shared `rootOutputDirectory`, and resets compiler, classloader, and in-memory caches. On the bad-tree copy, `cleanFull ; junitRoot/testFull` regenerated only the requested JUnit outputs, ran all six tests, and exited 1 (`handover-cleanfull.log`). For the default SBT 2 shared-output layout, `cleanFull` is the canonical built-in full build-output purge across disconnected aggregation graphs. It is a physical shared-output/cache purge, not per-project iteration; explicit aggregation-root cleans remain necessary if a build relies on disconnected projects' custom clean side effects or output paths outside the shared root output.

### Correct scoped clean

On the same reproduced bad state, this exact SBT 2.0.6 command worked:

```text
;set Global / localCacheDirectory := java.nio.file.Files.createTempDirectory("sbt2-clean-junitroot-").toFile ; junitRoot/clean ; junitRoot/testFull
```

`clean-scope-junitroot.log` proves that aggregate clean deleted both child projects' existing `test-classes` and `test-zinc`, including the two JUnit class files and both analysis links. Compilation then parsed both new class files, discovered both suites, ran six methods, reported two intentional failures, raised `sbt.TestsFailedException`, and exited 1.

The compact slash syntax `junitRoot/clean` is accepted by SBT 2.0.6 and matches the existing TeamCity command's `junitRoot/testFull` style. The spaced scoped-key form is useful in build definitions and `show`/`set` expressions, but the compact form avoids command-string tokenization ambiguity here.

## Controlled good-to-bad reproduction

The strongest runtime reproduction used one fixed project path, with no logger:

1. Start without `target/out`.
2. Run `junitRoot/testFull` with a fresh local action cache.
3. Observe six tests and exit 1 (`controlled-good.log`).
4. Preserve both child `test-classes` directories but move only their good `test-zinc` directories aside.
5. Run the same command at the same project path with another fresh local action cache.
6. Observe javac compile both Java sources, then zero test execution and exit 0 (`controlled-after-analysis-loss.log`).

Artifact comparison from that reproduction:

| Analysis | Expanded size | Contents |
|---|---:|---|
| Good success-module analysis | 2480 bytes | Generated `JUnitSuccessTest.class`, binary class name, methods such as `additionWorks`, `org.junit.Test` |
| Good failure-module analysis | 2515 bytes | Generated `JUnitFailuresTest.class`, both intentional-failure methods, `org.junit.Test` |
| Recreated bad success-module analysis | 1683 bytes | Setup/classpath/source entry only; no generated class/API/method/annotation data |
| Recreated bad failure-module analysis | 1689 bytes | Setup/classpath/source entry only; no generated class/API/method/annotation data |

Both `.class` files still exist and receive new modification times during the bad run. Both resulting `definedTestNames/data` files are zero bytes.

A normal second clean invocation without deleting analysis continues to run all six tests (`controlled-second-clean.log`). The state does not arise merely from invoking `testFull`, using a new local cache, or rerunning a clean workspace; analysis loss/inconsistency while class files remain is the required trigger in this reproduction.

## Why Zinc creates the empty analysis

SBT 2.0.6 uses Zinc 2.0.4. In `AnalyzingJavaCompiler.scala`:

1. Before compilation, `IncrementalCommon.scala` lines 883-891 delete only products associated with invalidated sources in the previous analysis. With absent or product-free analysis, that product set is empty, so an untracked retained `.class` file survives.
2. Before javac, `AnalyzingJavaCompiler.scala` lines 144-150 enumerate and memoize all class-file **paths** already present in the output directory.
3. Javac compiles the Java sources at lines 165-199.
4. After javac, lines 231-245 enumerate class-file paths again.
5. Line 237 defines generated classes as:

   `Set(classes.paths*) -- oldClasses`

6. Only those path-new classes are passed to `JavaAnalyze`.

If a prior analysis is absent or has no products, Zinc has no analyzed product relation telling its class-file manager to delete the existing class. Javac overwrites the same `.../JUnitSuccessTest.class` or `.../JUnitFailuresTest.class` path. The post-javac set of paths is therefore identical to the pre-javac set, `newClasses` is empty, and `JavaAnalyze` reports no generated class/API/test annotation. The saved analysis records the source and setup but no discoverable class.

This algorithm is unchanged on the current Zinc `2.0.x` and `develop` branches as checked on 2026-08-20. The mechanism is framework-independent Java compilation behavior; JUnit makes it visible because SBT test discovery relies on the compilation analysis.

## Scope

Observed scope:

- SBT 2.0.6;
- Java-only JUnit 4 test modules in the reused aggregate demo;
- retained/restored `target/out` whose class outputs are no longer coupled to a complete available Zinc analysis.

Not established as a general limitation:

- aggregate roots;
- `testFull`;
- JUnit 4 or `junit-interface` generally;
- the TeamCity logger/listener/runner;
- all clean SBT 2 workspaces.

The underlying Zinc path-difference mechanism is not JUnit-specific and could affect other Java sources/frameworks if the same inconsistent output/analysis state is created. Dedicated clean SBT 2 JUnit 4 and Jupiter fixtures passed, as did the clean legacy aggregate. ScalaTest and MUnit fixtures do not exercise this exact Java-analysis path.

The same path-difference code also exists on Zinc's 1.12.x branch, but SBT 1 demo cleaning/output behavior did not create the inconsistent state in these runs. This investigation does not claim that only SBT 2 can ever encounter the underlying Zinc condition.

## Remediation

### Disposable TeamCity demo: recommended

Use SBT's correctly scoped aggregate clean. The smallest robust edit to the existing SBT 2 command is:

```text
;set Global / localCacheDirectory := java.nio.file.Files.createTempDirectory("sbt2-fresh-action-cache-").toFile ; junitRoot/clean ; clearCaches ; junitRoot/testFull
```

That is, replace `clean ; cleanFiles` with `junitRoot/clean`. `clearCaches` may remain for the demo's independent cache-isolation intent, but it is not needed for this repair. Explicit child cleans also work but duplicate the aggregation relationship and are less maintainable.

This SBT-native change is preferable to external deletion because it:

- scopes cleanup to exactly the aggregate being tested;
- lets SBT delete its registered target, task-output, and stream state together;
- avoids hard-coding SBT 2's normalized `target/out/jvm/u/...` layout;
- does not discard outputs for unrelated demo modules.

External removal of `sbt-demo/sbt-2.0/target/out` remains a robust fallback for a disposable workspace, and a clean checkout remains the strongest isolation option. They are broader than necessary for this configuration.

Compared with the alternatives:

- `junitRoot/clean` is the smallest SBT-native repair for this TeamCity configuration and runs the affected aggregate's project clean semantics.
- `root/clean ; junitRoot/clean` runs project clean semantics across both known aggregation graphs, but must be maintained if new disconnected roots are added.
- `cleanFull` is the broad SBT 2 full-output/cache command for the default shared layout; it discards unrelated incremental outputs.
- deleting `target/out` externally has the same broad output-removal effect in this demo, but bypasses SBT's cache reset and any custom clean behavior or nonstandard output paths.

### Narrow repair if other SBT 2 output must be retained

For the current demo layout, delete both of these entries for each affected child project before compilation:

```text
target/out/jvm/u/module8junitsuccess/test-classes
target/out/jvm/u/module8junitsuccess/test-zinc
target/out/jvm/u/module9junitfailures/test-classes
target/out/jvm/u/module9junitfailures/test-zinc
```

Deleting only one member of each pair is not reliable. Prefer `junitRoot/clean` to manipulating these SBT implementation paths directly.

### Insufficient remedies

The following did not repair the state:

- unscoped `clean` from the unrelated current `root` aggregate;
- `all clean` from that same current-project aggregation graph;
- invoking `cleanFiles` as if it were a deletion task;
- `clearCaches`;
- a fresh `Global / localCacheDirectory`;
- deleting `target/out/value`;
- deleting test task streams/`definedTestNames`;
- deleting `test-zinc` without deleting the corresponding `test-classes`;
- switching from `test` to `testFull`;
- disabling, changing, or upgrading the TeamCity logger.

## Production-code implications

No change should be made to `TCReportListener`, the test result logger, task-output controls, runner parameters, or JUnit adapter for this anomaly.

The separate result-logger defect remains real: build 1404 ran failing tests but the old no-op result logger allowed exit 0. The stable logging controls work addressed that failure-semantic issue. It is independent of the later empty-discovery state.

An upstream Zinc issue/regression test may be worthwhile: start with a Java output directory containing an existing class file and no previous analysis, compile the matching source, and assert the resulting analysis includes the overwritten product. That is follow-up work, not a reason to modify the TeamCity logger.

## Remaining risks and unknowns

1. The exact filesystem event between TeamCity builds 1404 and 1406 that first lost or invalidated the good analysis link is not preserved. The retained-target/CAS coupling is strongly implicated, but attributing it specifically to one cache-setting toggle would overstate the evidence.
2. Zinc `develop` still uses the same path-only before/after difference, so a comparable inconsistent Java output/analysis restore can still reproduce the mechanism unless surrounding tooling removes untracked outputs.
3. External removal of all `target/out` makes disposable validation reliable at the cost of eliminating unrelated incremental build reuse. `junitRoot/clean` avoids that cost for this demo.
4. A successful zero-test task is normal to SBT when `definedTests` is empty; SBT cannot infer that test classes were expected. CI-level “expected test count > 0” assertions can guard demos against silent recurrence.

## Commands used

The exact launcher paths and cache setup are captured in `run-case.sh`. Representative commands were equivalent to:

```sh
java \
  -Dsbt.global.base="$CASE_GLOBAL" \
  -Dsbt.boot.directory="$SBT_BOOT" \
  -Dsbt.coursier.home="$COURSIER_HOME" \
  -Dsbt.ivy.home="$IVY_HOME" \
  -jar "$SBT_2_0_6_LAUNCHER" \
  ';set Global / localCacheDirectory := java.nio.file.Files.createTempDirectory("sbt2-investigation-").toFile ; clean ; cleanFiles ; clearCaches ; junitRoot/testFull'
```

Selective cleanup cases were made from copies of the stale demo beneath the evidence root. No original checkout, production source, commit, branch history, remote, or TeamCity production configuration was modified.

The scoped-clean follow-up used these exact task sequences on independent bad-state copies:

```text
;set Global / localCacheDirectory := java.nio.file.Files.createTempDirectory("sbt2-clean-unscoped-").toFile ; set Global / logLevel := Level.Debug ; clean ; junitRoot/testFull
;set Global / localCacheDirectory := java.nio.file.Files.createTempDirectory("sbt2-clean-all-").toFile ; set Global / logLevel := Level.Debug ; all clean ; junitRoot/testFull
;set Global / localCacheDirectory := java.nio.file.Files.createTempDirectory("sbt2-clean-junitroot-").toFile ; set Global / logLevel := Level.Debug ; junitRoot/clean ; junitRoot/testFull
;set Global / localCacheDirectory := java.nio.file.Files.createTempDirectory("sbt2-clean-explicit-").toFile ; set Global / logLevel := Level.Debug ; module8JUnitSuccess/clean ; module9JUnitFailures/clean ; junitRoot/testFull
```

An independent final audit reran the exact recommended sequence, including `clearCaches`, on another copy of the known-bad tree:

```text
;set Global / localCacheDirectory := java.nio.file.Files.createTempDirectory("sbt2-handover-scoped-clean-").toFile ; junitRoot/clean ; clearCaches ; junitRoot/testFull
```

It compiled both Java suites, ran all six methods, reported the two intentional failures, raised `sbt.TestsFailedException`, and exited 1 (`handover-scoped-clean.log`).

## Files changed by this investigation

- This untracked report only: `.agents/reports/sbt2-junit-aggregate-testfull-investigation.md`.
- Runtime evidence, temporary project copies, downloaded SBT/Zinc sources, and logs are under ignored `target/investigation/sbt2-junit-aggregate/`.
- No production logger or runner source changed.
- No commit, push, merge, amend, fixup, or history rewrite was performed.
