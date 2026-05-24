# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- KoverGate now automatically disables the Gradle `jacoco` plugin's instrumentation of `Test` tasks
  when `kover { useJacoco() }` is detected. The manual `tasks.withType<Test>().configureEach { ... }`
  workaround documented in the Scenario 2 setup is no longer required.

## [0.3.0-alpha01] - 2026-05-24

### Added

- `koverGateScopedReport` task that emits a gap report filtered to a selectable scope. The initial `branch` scope keeps only uncovered/missed-branch lines changed on the current branch relative to a base ref — diffing the working tree against the merge-base, so committed *and* uncommitted changes are included. Base ref defaults to `main` (override with `--base` per module or `-PkoverGateBase=<ref>` across a multi-module build). Applying the plugin to the root project also aggregates submodule scoped reports into one `AggregateGapReport`. Output is the standard gap-report shape with `metrics` omitted and `passed` true only when no in-scope gaps remain, written to `build/reports/kover/scoped-gap-report.json`.
- `xmlReportFile` DSL property in the `koverGate {}` block for gating on an externally produced JaCoCo XML (e.g. a `JacocoReport` task that merges unit-test and Android instrumented-test execution data). When set, the Kover plugin is optional — it need not be applied at all.

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

[Unreleased]: https://codeberg.org/commonsguy/koverGate/compare/0.3.0-alpha01...HEAD
[0.3.0-alpha01]: https://codeberg.org/commonsguy/koverGate/compare/0.2.0...0.3.0-alpha01
[0.2.0]: https://codeberg.org/commonsguy/koverGate/compare/0.1.3...0.2.0
[0.1.3]: https://codeberg.org/commonsguy/koverGate/compare/0.1.2...0.1.3
[0.1.2]: https://codeberg.org/commonsguy/koverGate/compare/0.1.1...0.1.2
[0.1.1]: https://codeberg.org/commonsguy/koverGate/compare/0.1.0...0.1.1
[0.1.0]: https://codeberg.org/commonsguy/koverGate/src/tag/0.1.0
