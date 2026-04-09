# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[0.1.1]: https://codeberg.org/commonsguy/koverGate/compare/0.1.0...0.1.1
[0.1.0]: https://codeberg.org/commonsguy/koverGate/src/tag/0.1.0
