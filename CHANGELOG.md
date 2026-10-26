# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.8.0] - 2026-10-26

### Added

- **`koverGateScopedReport --ignore-space-change`.** Leaves out lines whose only change on the branch is the amount
  of whitespace, such as re-indentation from wrapping a block, by diffing with `git diff --ignore-space-change`.
  Edits that add whitespace where there was none still count. It is off by default. For multi-module runs, set
  `-PkoverGateIgnoreSpaceChange=true`, as `-PkoverGateBase` does for `--base`.
- **Scoped reports now carry a `scope` block**, `{ "type": "branch", "ignoreSpaceChange": false }`, recording the
  settings the report was filtered with. It is absent from `gap-report.json`.
- **Kover 0.9.9 through 0.9.11 is supported.** The functional tests run against both ends of that range
  (`./gradlew functionalTestKoverMatrix`); versions in between are expected to work but are not tested.
- **KoverGate is now also published to Maven Central**, including the plugin marker, so builds that only allow Maven
  Central can apply it with `mavenCentral()` in `pluginManagement.repositories`.

### Changed

- Bumped Kover dependency from 0.9.9 to 0.9.11.

### Fixed

- **`xmlReportTask` now works for Kover's Android variant tasks.** Setting
  `xmlReportTask.set("koverXmlReportDebug")` on an Android module failed at configuration with "xmlReportTask ...
  not found", because koverGate looked the task up before Kover had registered it. The task is now resolved lazily,
  when the task graph is built, in both the auto-detected and `xmlReportFile` override branches, and the producer
  stamp attaches to it whenever it is registered. A misspelled name still fails clearly, but at that point rather than
  at configuration: the gate tasks report the `not found` message plus the available `koverXmlReport*` tasks, and
  `koverGateDoctor` prints `Kover XML: unresolved` with the same message instead of failing.

## [0.7.0] - 2026-08-31

### Fixed

- **`koverGateScopedReport` no longer drops partially covered lines.** `BranchDiffScopeFilter`
  constructed its output `FileGap` positionally, so `partiallyCoveredLines` silently fell back to its
  default of empty instead of being filtered like `uncoveredLines` and `missedBranchLines`. Scoped
  reports now filter and emit `partiallyCoveredLines` the same way, and a file whose only in-scope
  gap is a partially covered line is retained (and can flip the scoped `passed` to `false`) rather
  than being silently omitted.

### Added

- **Instruction-leverage ordering** for `gap-report.json`'s `files` array: entries now sort by missed-instruction
  magnitude (most missed instructions first) instead of parse order, so the console output and the JSON both surface
  the highest-leverage gap first — closing a 43-instruction gap beats chasing a 1-instruction one. Each `FileGap`
  gains `missedInstructionsCold` (summed over `uncoveredLines`) and `missedInstructionsResidual` (summed over
  `partiallyCoveredLines`); both are `null` and omitted in scoped reports, where the per-line data needed to
  recompute them for a filtered subset of lines is unavailable. The console listing annotates each file with
  `[43 cold]`, `[4 cold, 27 residual]`, or `[branch arms only]` for a file whose only gap is missed branch arms on
  otherwise fully-covered lines.
- **Gap-kind classification** for `gap-report.json`'s `files` array: each `FileGap` now also carries `gapKind`
  (`cold`, `residual`, `mixed`, or `branchArmsOnly`), derived purely from which of `uncoveredLines` and
  `partiallyCoveredLines` are non-empty, plus `dominantGapKind` (`cold` or `residual`) naming whichever instruction
  bucket is larger — absent when the two are equal, including when both are zero. Both let a consuming agent branch
  on one value instead of cross-referencing the line arrays and counts; both are `null` and omitted in scoped
  reports for the same reason `missedInstructionsCold`/`missedInstructionsResidual` are. `residual` describes the
  shape of the evidence, not a verdict — residual missed instructions still count toward the coverage metrics.
- A **report-contention guard**: koverGate now detects when the Kover (or JaCoCo) XML it gates on is rewritten by
  another task during the same build. A rewrite *before* the gate reads the file means the gate parsed a foreign
  report and the verdict may be wrong — this is recorded in `gap-report.json` as `source.rewrittenBeforeRead` and,
  by default, logged as a warning; set `failOnContendedReport.set(true)` to fail the build on it instead. A rewrite
  *after* the gate reads the file does not affect that build's verdict but flags the report path as contended, and
  is logged at build end. `xmlReportTask` now also identifies the producer task in the `xmlReportFile` override
  branch (previously only used to select which Kover variant to auto-detect), which is what lets the guard fully
  apply there too; leaving it blank in that branch falls back to after-read-only detection, reported by
  `koverGateDoctor` as `Report-contention guard: PARTIAL`.
- A new **`koverGateDelta`** task reports how coverage moved since the previous build. `koverGateGenerateReport` now
  rotates its just-written `gap-report.json` aside to `gap-report-baseline.json` on every run, so "the previous
  build" is always well-defined regardless of git state; `koverGateDelta` reads that baseline plus the current
  report and writes `build/reports/kover/gap-delta.json`, with a `baseline` field of `"present"` or `"absent"` (the
  latter never synthesized as all-zeros) and file-level (not line-level) instruction and line-count deltas. Like
  `koverGateBump` and `koverGateDoctor`, it is diagnostic-only and never fails the build.
- **Threshold headroom** on every metric: `gap-report.json` and the console output now report `headroom`, the
  covered units separating a metric from its threshold — positive with room to spare, negative when short by that
  many (`needs N more <unit>` in the console), `null` when the metric has zero instrumented units
  (`[not instrumented]` in the console). It prints on every metric line, pass and fail alike, since a thin passing
  headroom is a standing risk worth seeing before it bites. `koverGateDelta`'s `gap-delta.json` gains the matching
  `headroomDelta` and the current `headroom` on each metric, recomputed from each side's covered/total/threshold so
  older baselines still work; the console delta summary shows the movement inline, e.g. `(headroom 9 → 1)`.

### Changed

- Bumped Kover dependency from 0.9.8 to 0.9.9.

### Fixed

- **`koverGateScopedReport` no longer silently excludes untracked files.** Brand-new, never-`git add`-ed source
  files were invisible to the scoped report's git diff, so a fresh uncovered file could produce a misleading
  `passed: true, files: []`. Every gap line in an untracked, non-gitignored file is now in scope by definition;
  the console summary notes the count (e.g. `, 1 untracked`) when it is non-zero.

## [0.6.0] - 2026-07-18

### Added

- A new **`excludes {}`** DSL inside `koverGate {}` for excluding classes and packages from
  coverage at parse time: `koverGate { excludes { classes("*.Fixture*"); packages("com.example.generated") } }`.
  Patterns use the same wildcard semantics as Kover's own `excludes {}` (`*` crosses dots, `?` matches
  one character, `#` matches within a package segment), so an existing `kover { … excludes { } }` block
  can usually be copied over almost verbatim — except `annotatedBy(...)`, which has no equivalent here
  (the XML report carries no per-class annotation data) and fails to compile with a clear "unresolved
  reference" error if pasted in.
  Because the filter is applied by koverGate itself while reading the Kover XML — via its own
  `SetProperty<String>` task input — it does not depend on Kover's own excludes surviving into the
  report. This defends two previously-fragile paths: the configuration-cache filter-loss case (Kover's
  in-memory excludes do not always survive a cached configuration), and the `attributeCoverageFrom`
  aggregate write-race (the attributed module's own classes are now excluded via this same mechanism,
  rather than relying on timing between the two modules' report writes).

## [0.5.0] - 2026-06-27

### BREAKING CHANGES

- Aggregate root projects now receive a minimal `RootCoverageGateExtension` (exposing only `prettyPrintJson`) instead
  of the full `CoverageGateExtension`. Coverage thresholds were never enforced at the aggregate root. **This change
  makes that a compile-time DSL error**: using `minLineCoverage`, `minBranchCoverage`, etc. in `koverGate {}` on a root
  project now produces an "unresolved reference" at build-script compilation time.

### Added

- A **producer-reuse freshness warning**: at build completion, KoverGate now warns when a module's `koverGateReport`
  gate ran (executed or failed) while Gradle served that module's `koverXmlReport*` task up-to-date or from cache —
  the situation where a coverage verdict can look wrong because the Kover XML was not regenerated. The message names
  the module and the reuse outcome and suggests `--rerun-tasks`. It is implemented with a configuration-cache-safe
  build service that observes task outcomes and logs at build's end. Like all freshness signals, it never fails the
  build.
- The gap report JSON now reports **partially covered lines** separately. Each file entry carries a `partiallyCoveredLines`
  array — source lines with both covered and missed instructions (`ci > 0 && mi > 0`), mostly exercised but retaining a
  residual that is frequently synthetic duplicate bytecode (inlined lambdas, coroutine state machines) rather than
  untested logic. Each `missedBranchLines` entry also gains a `fullyInstructionCovered` boolean — `true` when every
  instruction on the line is covered and only a branch arm is missed, a strong hint the arm is synthetic or unreachable.
- A new **`koverGateBump`** Gradle task that prints suggested `koverGate {}` threshold updates derived from a module's
  current gap report — one `min<Metric>Coverage.set(N)` line per metric, computed as `floor(coverage) - margin` and
  annotated with its prior value, ready to merge into the existing block. It ratchets up by default (only metrics whose
  coverage now exceeds their threshold); pass `--allow-lower` to also emit reductions and `--margin=<n>` to leave
  headroom. Disabled metrics are skipped, it emits only the threshold lines (never a whole block), it never edits build
  scripts, and it never fails the build.

### Changed

- The existing build-script staleness check is now explicitly labeled a **build-script timestamp heuristic** in both
  the `koverGateGenerateReport` freshness warning and the `koverGateDoctor` output, to make clear it is a coarse
  timestamp proxy rather than a definitive verdict. No behavior or JSON change.
- `uncoveredLines` in `gap-report.json` is now derived from **covered instructions** (`ci == 0`) rather than missed
  instructions (`mi > 0`). A line that is partially covered — some instructions covered, some missed — is no longer
  reported as uncovered; it moves to the new `partiallyCoveredLines` array. This aligns the per-file gap list with
  Kover's module-level `LINE` counter (which already counts a partially covered line as covered), so the report no
  longer contradicts the threshold it explains, nor sends developers to write tests for already-covered lines. The gate
  pass/fail math is unchanged.

## [0.4.0] - 2026-06-20

### Added

- `koverGateDoctor` task (leaf modules): prints the configured thresholds, the Kover XML the gate will read,
  that XML's freshness, and the packages excluded via `attributeCoverageFrom` — a one-command triage for "why
  does the gate say that?" It never fails the build and never runs tests.
- The gap report JSON now includes a `source` block recording the Kover XML path, its last-modified time, the
  newest build-script modification time, and a `stale` flag. When the XML predates the module's build scripts,
  `koverGateGenerateReport` logs a freshness warning suggesting `--rerun-tasks`. The warning is a coarse
  heuristic and never fails the build.
- The gap report JSON now reports each missed-branch line as a `{ "line", "missed", "total" }` object instead of
  a bare line number, so an unreachable branch arm (`1/2`) reads differently from a genuinely untested one
  (`2/2`). The console shows the same ratio inline, e.g. `Branches 123 (1/2), 140 (2/2) missed (3 of 4)`.

### Changed

- **Breaking (JSON):** `missedBranchLines` in `gap-report.json` (and the scoped report) changed from an array
  of line numbers (`[42]`) to an array of objects (`[{ "line": 42, "missed": 1, "total": 2 }]`). Consumers
  parsing the integer array must update.
- `koverGateGenerateReport` is no longer cached and always re-runs. Parsing the Kover XML into the gap report
  JSON is cheap, and always running it ensures the staleness check is re-evaluated every build so a stale gap
  report can never be served from an up-to-date or cached result. The gate task (`koverGateReport`) still stays
  up-to-date when the JSON is unchanged.
- `koverGate { attributeCoverageFrom(":module", excludePackages = listOf(...)) }` attributes another module's
  test coverage to this module's gate. It wires Kover's cross-module aggregation so the named module's
  coverage is merged into this module's report, and excludes the named packages (the contributing module's
  own classes) so they are not judged against this module's thresholds. Useful for conformance/acceptance
  modules that exercise a library's code. Requires the Kover plugin; creates no dependency cycle; may be
  called multiple times.

## [0.3.0] - 2026-05-24

### Added

- `koverGateScopedReport` task that emits a gap report filtered to a selectable scope. The initial `branch` scope keeps only uncovered/missed-branch lines changed on the current branch relative to a base ref — diffing the working tree against the merge-base, so committed *and* uncommitted changes are included. Base ref defaults to `main` (override with `--base` per module or `-PkoverGateBase=<ref>` across a multi-module build). Applying the plugin to the root project also aggregates submodule scoped reports into one `AggregateGapReport`. Output is the standard gap-report shape with `metrics` omitted and `passed` true only when no in-scope gaps remain, written to `build/reports/kover/scoped-gap-report.json`.
- `xmlReportFile` DSL property in the `koverGate {}` block for gating on an externally produced JaCoCo XML (e.g. a `JacocoReport` task that merges unit-test and Android instrumented-test execution data). When set, the Kover plugin is optional — it need not be applied at all.
- `koverGateGenerateReport` task (leaf modules): parses Kover XML and writes the gap report JSON
  without gating the build. `koverGateReport` now depends on it as a thin gate step.
- KoverGate now automatically disables the Gradle `jacoco` plugin's instrumentation of `Test` tasks
  when `kover { useJacoco() }` is detected. The manual `tasks.withType<Test>().configureEach { ... }`
  workaround documented in the Scenario 2 setup is no longer required.

### Changed

- `koverGateScopedReport` now depends on `koverGateGenerateReport` instead of `koverGateReport`.
  Running `koverGateScopedReport` no longer requires `--continue` when coverage is below threshold —
  the scoped report is always reachable.

## [0.2.0] - 2026-05-03

### Added

- Multi-module aggregate gap report: applying `com.commonsware.kovergate` to the root project of a multi-module build now generates `build/reports/kover/gap-report.json` at the root, merging all per-submodule gap reports into a single `AggregateGapReport` (with a `modules` array)

## [0.1.3] - 2026-04-29

### Changed

- Bumped detektifier from 0.1.1 to 0.1.2
- Bumped Dokka from 2.1.0 to 2.2.0
- Bumped JUnit from 5.11.4 to 5.14.4
- Bumped kotlinx-serialization from 1.6.3 to 1.11.0
- Bumped ktfmt-gradle from 0.25.0 to 0.26.0
- Upgraded Gradle wrapper from 8.10.2 to 9.5.0

## [0.1.2] - 2026-04-14

### Changed

- Bumped Kover dependency from 0.9.1 to 0.9.8

## [0.1.1] - 2026-04-09

### Added

- `xmlReportTask` DSL property in the `koverGate {}` block for specifying the Kover XML report task by name; enables Android projects to select a build-variant report (e.g., `xmlReportTask.set("koverXmlReportDebug")`)

### Fixed

- Plugin now emits a descriptive error when no Kover XML report task can be auto-detected, rather than failing with an opaque "no value for `xmlReportFile`" input-validation error

## [0.1.0] - 2026-03-27

### Added

- `koverGateReport` Gradle task that gates the build on Kover coverage thresholds
- Configurable minimum thresholds (0–100) for five metrics: line, branch, instruction, method, and class coverage; all default to 100
- `disabledMetrics` option to exclude specific metrics from enforcement
- Human-readable console summary with `[PASS]`/`[FAIL]` indicators per metric
- JSON gap report written to `build/reports/kover/gap-report.json` on every run, including per-file lists of uncovered lines and missed-branch lines
- `prettyPrintJson` option for indented JSON output
- Automatic wiring to Kover's XML report task; prefers `koverXmlReportJvm` when present
- Build cache support via `@CacheableTask`; the task is UP-TO-DATE when inputs have not changed
- `skills/kover-gate-report.md` skill file for teaching AI coding agents to use the plugin

[Unreleased]: https://codeberg.org/commonsguy/koverGate/compare/0.8.0...HEAD
[0.8.0]: https://codeberg.org/commonsguy/koverGate/compare/0.7.0...0.8.0
[0.7.0]: https://codeberg.org/commonsguy/koverGate/compare/0.6.0...0.7.0
[0.6.0]: https://codeberg.org/commonsguy/koverGate/compare/0.5.0...0.6.0
[0.5.0]: https://codeberg.org/commonsguy/koverGate/compare/0.4.0...0.5.0
[0.4.0]: https://codeberg.org/commonsguy/koverGate/compare/0.3.0...0.4.0
[0.3.0]: https://codeberg.org/commonsguy/koverGate/compare/0.2.0...0.3.0
[0.2.0]: https://codeberg.org/commonsguy/koverGate/compare/0.1.3...0.2.0
[0.1.3]: https://codeberg.org/commonsguy/koverGate/compare/0.1.2...0.1.3
[0.1.2]: https://codeberg.org/commonsguy/koverGate/compare/0.1.1...0.1.2
[0.1.1]: https://codeberg.org/commonsguy/koverGate/compare/0.1.0...0.1.1
[0.1.0]: https://codeberg.org/commonsguy/koverGate/src/tag/0.1.0
