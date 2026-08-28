# koverGate

A Gradle plugin that gates builds on [Kover](https://github.com/Kotlin/kotlinx-kover) coverage thresholds and generates structured gap reports. The objective is to make it easier for coding agents to process coverage results and identify where more tests are required.

When the `koverGateReport` task runs, it reads Kover's XML coverage report, checks each enabled metric against its configured minimum, and fails the build if any metric falls short. It always writes a JSON report to `build/reports/kover/gap-report.json` — even on success — so that both humans and automated agents can consistently parse the results.

## Requirements

- Gradle 8+
- Kotlin DSL (`build.gradle.kts`)
- [Kover Gradle plugin](https://github.com/Kotlin/kotlinx-kover) applied to the same module (optional when using `xmlReportFile` — see [Android: Merging Unit + Instrumented Coverage](#android-merging-unit--instrumented-coverage))

## Adding the Plugin

The plugin is published to the Gradle Plugin Portal. Apply it alongside Kover in your module's `build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.commonsware.kovergate") version "0.6.0"
}
```

No additional repository declarations are needed if you already have `gradlePluginPortal()` in your `settings.gradle.kts` plugin management block:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

## Configuring the Plugin

All `koverGate {}` properties are optional. If you are happy with the defaults (100% coverage on every metric), you can skip the `koverGate {}` block entirely. The defaults are:

```kotlin
koverGate {
    minLineCoverage.set(100)
    minBranchCoverage.set(100)
    minInstructionCoverage.set(100)
    minMethodCoverage.set(100)
    minClassCoverage.set(100)
    disabledMetrics.set(emptySet())
    prettyPrintJson.set(false)
}
```

To lower a threshold, disable a metric, or change the report format, set only the properties you want to override:

```kotlin
koverGate {
    // Lower thresholds for modules where 100% is not the right target.
    minLineCoverage.set(90)
    minBranchCoverage.set(80)
    minInstructionCoverage.set(90)
    minMethodCoverage.set(95)
    minClassCoverage.set(100)

    // Exclude specific metrics from enforcement entirely.
    // Excluded metrics are omitted from the JSON report as well.
    disabledMetrics.set(setOf(CoverageMetric.BRANCH))

    // Pretty-print the JSON gap report (useful for debugging).
    prettyPrintJson.set(true)

    // Kover XML report task to wire to. Required for Android projects — see below.
    // Defaults to auto-detecting koverXmlReportJvm or koverXmlReport.
    xmlReportTask.set("koverXmlReportDebug")
}
```

If you do not need to change any of the configuration defaults, you can skip the `koverGate {}` call.

### Available Metrics

| Constant | Enforced by default |
|---|---|
| `CoverageMetric.LINE` | Yes |
| `CoverageMetric.BRANCH` | Yes |
| `CoverageMetric.INSTRUCTION` | Yes |
| `CoverageMetric.METHOD` | Yes |
| `CoverageMetric.CLASS` | Yes |

### Android Projects

For Android projects, Kover generates build-variant-specific XML report tasks (`koverXmlReportDebug`, `koverXmlReportRelease`, etc.) rather than the `koverXmlReportJvm` task used by JVM modules. Set `xmlReportTask` to tell koverGate which variant to gate on:

```kotlin
koverGate {
    xmlReportTask.set("koverXmlReportDebug")
}
```

If you forget to set this and no auto-detectable task exists, the build will fail with a message listing the available `koverXmlReport*` tasks to help you choose the right name.

### Android: Merging Unit + Instrumented Coverage

Kover alone cannot see coverage from `androidTest/` instrumented tests — it only instruments JVM-based test tasks. AGP can produce JaCoCo coverage data for connected tests, and merging both at the execution-data level gives a complete picture: a line covered by *either* test type counts as covered.

koverGate supports this via the `xmlReportFile` property. When set, koverGate reads coverage from that file instead of auto-detecting a Kover task, and the Kover plugin becomes fully optional.

#### Scenario 1: Kover only (unit tests)

Kover instruments only JVM-based test tasks. Instrumented tests (`androidTest/`) are not included in coverage. This is the same setup shown above in [Android Projects](#android-projects):

```kotlin
plugins {
    id("com.android.library") version "8.11.2"
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.commonsware.kovergate") version "0.6.0"
}

koverGate {
    xmlReportTask.set("koverXmlReportDebug")
}
```

#### Scenario 2: Kover + AGP merged (recommended for Android libraries)

Apply Kover with `useJacoco()` so it emits JaCoCo-compatible exec data, then merge it with AGP's instrumented-test exec using a `JacocoReport` task:

```kotlin
plugins {
    id("com.android.library") version "8.11.2"
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.commonsware.kovergate") version "0.6.0"
    jacoco
}

android {
    buildTypes {
        debug {
            // Kover owns unit-test exec via useJacoco(); AGP owns instrumented-test exec.
            enableAndroidTestCoverage = true
        }
    }
}

// koverGate automatically disables Gradle jacoco plugin Test instrumentation
// when useJacoco() is detected — no manual tasks.withType<Test> block needed.
kover { useJacoco() }

val mergedCoverage = tasks.register<JacocoReport>("mergedCoverage") {
    dependsOn("testDebugUnitTest", "createDebugCoverageReport")
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("kover/bin-reports/testDebugUnitTest.exec")          // Kover unit-test exec
            include("outputs/code_coverage/debugAndroidTest/connected/**/*.ec") // AGP instrumented
        }
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory) {
            include("**/kotlin-classes/debug/**", "**/javac/debug/**/classes/**")
            exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*")
        }
    )
    reports { xml.required.set(true) }
}

koverGate {
    // Use map {} on the TaskProvider — flatMap { it.reports.xml.outputLocation } triggers a
    // Gradle 9.5 validation error on DefaultSingleFileReport; map {} carries the same dependency.
    xmlReportFile.set(
        mergedCoverage.map { layout.buildDirectory.file("reports/jacoco/mergedCoverage/mergedCoverage.xml").get() }
    )
}
```

#### Scenario 3: AGP-only (no Kover)

When Kover is not applied at all, the Gradle `jacoco` plugin handles unit-test exec and AGP handles instrumented-test exec:

```kotlin
plugins {
    id("com.android.library") version "8.11.2"
    id("org.jetbrains.kotlin.android")
    id("com.commonsware.kovergate") version "0.6.0"
    jacoco
}

android {
    buildTypes {
        debug { enableAndroidTestCoverage = true }
    }
}

val mergedCoverage = tasks.register<JacocoReport>("mergedCoverage") {
    dependsOn("testDebugUnitTest", "createDebugCoverageReport")
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec")                            // Gradle jacoco plugin
            include("outputs/code_coverage/debugAndroidTest/connected/**/*.ec") // AGP instrumented
        }
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory) {
            include("**/kotlin-classes/debug/**", "**/javac/debug/**/classes/**")
            exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*")
        }
    )
    reports { xml.required.set(true) }
}

koverGate {
    xmlReportFile.set(
        mergedCoverage.map { layout.buildDirectory.file("reports/jacoco/mergedCoverage/mergedCoverage.xml").get() }
    )
}
```

Runnable reference samples for both scenarios are in [`../koverGate-samples/android-merged`](../koverGate-samples/android-merged) and [`../koverGate-samples/android-agp-only`](../koverGate-samples/android-agp-only).

### Multi-Module Projects

For multi-module builds, apply the plugin to the root project as well as each submodule. The root project does not require the Kover plugin.

**Root `build.gradle.kts`:**
```kotlin
plugins {
    id("com.commonsware.kovergate") version "0.6.0"
}
```

The root `koverGate {}` block only exposes `prettyPrintJson` — threshold properties such as `minLineCoverage` are not
available at the root and produce a compile-time error if attempted. Coverage thresholds are enforced per leaf module.
If you do not need pretty-printed aggregate JSON, you can omit `koverGate {}` at the root entirely.

**Each submodule `build.gradle.kts`:**
```kotlin
plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.commonsware.kovergate") version "0.6.0"
}
```

When the root project has subprojects, koverGate registers an aggregate `koverGateReport` task at root that depends on all submodule `koverGateReport` tasks. Running:

```bash
./gradlew koverGateReport
```

runs every submodule task, then writes an aggregate `build/reports/kover/gap-report.json` at the root. The aggregate report contains a `modules` array — one `GapReport` entry per submodule — and a top-level `passed` field that is `true` only when every module passed.

**Note:** The aggregate report is only generated when all submodule tasks succeed. To run all checks even when some fail and still collect per-module JSON reports from passing modules, use `--continue`:

```bash
./gradlew koverGateReport --continue
```

Individual per-module reports remain at `<module>/build/reports/kover/gap-report.json` regardless of whether the root plugin is applied.

Each submodule can use any of the three Android scenarios — Kover-only (Scenario 1), Kover + AGP merged (Scenario 2), or AGP-only (Scenario 3). The root project behavior and aggregate report structure are the same regardless of how individual submodules produce their coverage XML.

## Running the Task

```bash
./gradlew :your-module:koverGateReport
```

The task depends on Kover's XML report task automatically; there is no need to run Kover separately first.

### Console Output

On failure, the task prints a summary to the build log and then fails:

```
Coverage gate FAILED for :your-module
  Line: 88.5% (177/200) [FAIL]  [min: 90%]
  Branch: 91.2% (52/57) [PASS]
  Instruction: 89.1% (890/999) [FAIL]  [min: 90%]
  Method: 96.0% (48/50) [PASS]
  Class: 100.0% (12/12) [PASS]

Files with gaps (most missed instructions first):
  Foo.kt  [61 cold, 8 residual]
    Lines 42, 57 uncovered
    Branches 42 (1/2) missed (1 of 2)
  Bar.kt  [19 cold]
    Line 113 uncovered
```

### JSON Gap Report

The report is written to `<module>/build/reports/kover/gap-report.json` on every run:

```json
{
  "module": ":your-module",
  "passed": false,
  "metrics": {
    "LINE":        { "covered": 177, "total": 200, "percent": 88.5, "threshold": 90, "passed": false },
    "INSTRUCTION": { "covered": 890, "total": 999, "percent": 89.1, "threshold": 90, "passed": false },
    "BRANCH":      { "covered":  52, "total":  57, "percent": 91.2, "threshold": 80, "passed": true  },
    "METHOD":      { "covered":  48, "total":  50, "percent": 96.0, "threshold": 95, "passed": true  },
    "CLASS":       { "covered":  12, "total":  12, "percent": 100.0,"threshold": 100,"passed": true  }
  },
  "files": [
    {
      "name": "Foo.kt",
      "package": "com/example",
      "uncoveredLines": [42, 57],
      "missedBranchLines": [{ "line": 42, "missed": 1, "total": 2, "fullyInstructionCovered": false }],
      "partiallyCoveredLines": [88],
      "missedInstructionsCold": 61,
      "missedInstructionsResidual": 8
    },
    {
      "name": "Bar.kt",
      "package": "com/example",
      "uncoveredLines": [113],
      "missedBranchLines": [],
      "partiallyCoveredLines": [],
      "missedInstructionsCold": 19,
      "missedInstructionsResidual": 0
    }
  ]
}
```

`uncoveredLines` lists only lines with **zero** covered instructions — genuinely never executed. A line that is partially covered (some instructions covered, some missed) appears in `partiallyCoveredLines` instead; its residual is frequently synthetic duplicate bytecode (inlined lambdas, coroutine state machines) that no ordinary test can close. On each `missedBranchLines` entry, `fullyInstructionCovered: true` flags a line whose instructions are all covered and only a branch arm is missed — usually a synthetic or unreachable arm.

`files` arrives ordered by missed-instruction leverage, most first: `missedInstructionsCold` (summed over `uncoveredLines`) descending, then `missedInstructionsResidual` (summed over `partiallyCoveredLines`) descending, then file name ascending. Closing a 43-instruction gap is worth more than closing a 1-instruction one, and this ordering surfaces that without requiring you to sum the line lists yourself. A file whose only gap is missed branch arms on otherwise fully-covered lines has both counts at `0` and renders as `[branch arms only]` in the console instead of a misleading `[0 cold]`. Treat the counts as a proxy for leverage, not a measure of it: they are bytecode instruction counts, not developer effort, and a single Kotlin line carrying an inlined lambda or a coroutine state machine can outweigh ten plain statements.

> **Note:** Every leaf-module `gap-report.json` also carries a `source` provenance block (after `files`) recording which XML the numbers came from and whether it is stale. It is omitted from the example above for brevity — see [Report Provenance & Freshness](#report-provenance--freshness). The aggregate report carries the same block inside each entry of its `modules` array.

### Aggregate JSON Structure

When the root project plugin is applied, the aggregate report at `build/reports/kover/gap-report.json` wraps each submodule's `GapReport` in a `modules` array:

```json
{
  "passed": true,
  "modules": [
    {
      "module": ":core",
      "passed": true,
      "metrics": { "LINE": { "covered": 200, "total": 200, "percent": 100.0, "threshold": 100, "passed": true } },
      "files": []
    },
    {
      "module": ":app",
      "passed": true,
      "metrics": { "LINE": { "covered": 150, "total": 150, "percent": 100.0, "threshold": 100, "passed": true } },
      "files": []
    }
  ]
}
```

## Scoped Gap Report (Changed Lines)

The `koverGateScopedReport` task filters the gap report to only the lines **changed on the current branch**, so agents and developers can triage coverage gaps without wading through pre-existing ones.

### What It Does

`koverGateScopedReport` depends on `koverGateGenerateReport` (the non-gating JSON writer) and reads its `gap-report.json`. It then diffs the working tree against the merge-base of the configured base ref and `HEAD` — capturing committed and uncommitted changes to tracked files, plus every gap line in untracked (never `git add`-ed), non-gitignored files in full — and keeps only the `uncoveredLines` and `missedBranchLines` entries that are in scope. The result is written to `build/reports/kover/scoped-gap-report.json`. Because it depends on the generate step rather than the gate, it always completes — even when overall coverage is below threshold.

> **Note:** Untracked, non-gitignored files are included in full — every gap line counts as in scope, since there is no prior committed version to diff against. Add a file to `.gitignore` if you need it excluded.

### Running It

**Single module** (base ref defaults to `main`):
```bash
./gradlew :your-module:koverGateScopedReport
```

**Override the base ref** for a module:
```bash
./gradlew :your-module:koverGateScopedReport --base=develop
```

**Multi-module** (use the `-PkoverGateBase` property — `--base` does not propagate across task dependencies):
```bash
./gradlew koverGateScopedReport -PkoverGateBase=main
```

This runs every submodule's `koverGateScopedReport` task and writes an aggregate scoped report at `build/reports/kover/scoped-gap-report.json`.

> **Note:** `koverGateScopedReport` does **not** fail the build on gaps. It is a reporting task only; the build gate remains `koverGateReport`.

### Scoped JSON Output

**Leaf module** (`<module>/build/reports/kover/scoped-gap-report.json`) — same shape as `gap-report.json` but `metrics` is always empty:

```json
{
  "module": ":your-module",
  "passed": false,
  "metrics": {},
  "files": [
    {
      "name": "Calculator.kt",
      "package": "com/example",
      "uncoveredLines": [12],
      "missedBranchLines": [{ "line": 15, "missed": 1, "total": 2 }]
    }
  ]
}
```

`missedInstructionsCold` and `missedInstructionsResidual` are absent here: the scoped filter narrows `gap-report.json`'s existing line lists to the changed subset, but it has no access to the per-line instruction counts needed to recompute the sums for that subset, so it leaves both fields unset rather than publish a number that no longer matches the filtered lines.

- `"passed": true` when `files` is empty (no in-scope gaps).
- `"passed": false` when at least one changed line is uncovered or has a missed branch.

**Root aggregate** (`build/reports/kover/scoped-gap-report.json`) — same shape as the full aggregate:

```json
{
  "passed": false,
  "modules": [
    {
      "module": ":core",
      "passed": true,
      "metrics": {},
      "files": []
    },
    {
      "module": ":app",
      "passed": false,
      "metrics": {},
      "files": [
        {
          "name": "Calculator.kt",
          "package": "com/example",
          "uncoveredLines": [12],
          "missedBranchLines": []
        }
      ]
    }
  ]
}
```

## Report Provenance & Freshness

Every gap report records **where its numbers came from**, so a surprising verdict can be diagnosed at a glance instead of by trial and error.

### The `source` block

`gap-report.json` includes a `source` object:

```json
{
  "module": ":your-module",
  "passed": true,
  "metrics": {},
  "files": [],
  "source": {
    "xmlPath": "/abs/path/to/build/reports/kover/report.xml",
    "xmlModified": "2026-06-18T14:03:11Z",
    "stale": false,
    "newestBuildScriptModified": "2026-06-17T09:12:44Z",
    "rewrittenBeforeRead": false
  }
}
```

- `xmlPath` — the Kover (or JaCoCo) XML the report was computed from.
- `xmlModified` / `newestBuildScriptModified` — ISO-8601 UTC timestamps.
- `stale` — `true` when the newest build script is newer than the XML, i.e. the on-disk coverage may predate your current build configuration.
- `rewrittenBeforeRead` — `true` when some other task rewrote the XML after its declared producer finished but before the gate read it, meaning **this build's verdict may be wrong**. See [Report-contention guard](#report-contention-guard) below.

### Freshness warning

When `stale` is `true`, `koverGateGenerateReport` logs a warning (it never fails the build):

```
koverGate: coverage report may be STALE.
  Kover XML: /abs/path/.../report.xml
  XML modified:          2026-06-18T14:03:11Z
  Build script modified: 2026-06-19T08:00:00Z
  The XML predates your build configuration; re-run with --rerun-tasks to regenerate coverage.
```

This catches the case where a cached or up-to-date Kover XML predates a change to your coverage configuration (for example, adding an `excludes` filter) and would otherwise produce a confident but wrong verdict. The signal is a coarse heuristic — any build-script edit can trigger it — so it is a *warning*, never a gate failure.

> **Why always re-run?** `koverGateGenerateReport` (the cheap XML→JSON step) is intentionally **not cached** and always runs, so the freshness check is re-evaluated on every build. The gate itself (`koverGateReport`) still stays up-to-date when the JSON is unchanged, so this adds no meaningful cost.

### Producer-reuse warning

The `stale` flag above is a build-script **timestamp heuristic**: it only compares the Kover XML's modification time to your build scripts'. It cannot tell whether Gradle actually regenerated the XML this build.

koverGate adds a second, complementary signal for exactly that. When a module's `koverGateReport` gate runs (executes or fails) while Gradle serves that module's `koverXmlReport*` task **up-to-date or from cache**, koverGate logs a warning at the end of the build:

```
koverGate freshness: the Kover XML for :app was reused (UP_TO_DATE) this build while its coverage gate ran; if the verdict looks wrong, re-run with --rerun-tasks to regenerate coverage.
```

This is the situation in which a verdict can look wrong because Gradle did not rebuild the Kover XML from fresh inputs. Like the staleness heuristic, it never fails the build — re-run with `--rerun-tasks` to force regeneration.

### Report-contention guard

The two signals above catch a report that is *old*. koverGate also detects a report that is *actively being rewritten by another task in the same build* — a real hazard if two tasks (e.g. a custom aggregation step and the gate itself) write to or read from the same XML path. This guard covers two distinct cases:

- **Before-read rewrite** — some other task overwrote the Kover XML after its declared producer finished but before `koverGateGenerateReport` read it. The gate parsed a foreign report, so **this build's verdict may be wrong**:

  ```
  KoverGate: coverage report for :app was REWRITTEN before it was read; the verdict may be wrong.
    Kover XML: /abs/path/.../report.xml
    Declared producer: :app:koverXmlReportJvm
    Producer wrote:  modified=2026-06-18T14:03:11Z size=48213
    Gate read:       modified=2026-06-18T14:03:12Z size=51900
    Some other task wrote this path after :app:koverXmlReportJvm finished; give the gate a report path nothing else writes.
  ```

  This finding is recorded in `gap-report.json` as `source.rewrittenBeforeRead`. By default it only warns; set `failOnContendedReport.set(true)` to hard-fail the build instead:

  ```kotlin
  koverGate {
      failOnContendedReport.set(true)
  }
  ```

- **After-read rewrite** — the XML changed again after the gate read it but before the build ended. The gate parsed the producer's own output, so this build's verdict is correct, but the path is contended and a future build may not be so lucky. This is console-only (it cannot reach `gap-report.json`, since it is discovered after the JSON is written) and never fails the build:

  ```
  koverGate: coverage report for :app was rewritten after :app's gate read it; this build's verdict is unaffected, but the report path is contended.
    Kover XML: /abs/path/.../report.xml
    Gate read:  modified=2026-06-18T14:03:11Z size=48213
    Now:        modified=2026-06-18T14:03:15Z size=52010
  ```

The guard needs a known **producer task** to stamp the XML's identity the instant it finishes — this is automatic when koverGate auto-detects the Kover XML task, and available in the [`xmlReportFile`](#android-merging-unit--instrumented-coverage) override branch by also setting `xmlReportTask` to name whatever task writes that file:

```kotlin
koverGate {
    xmlReportFile.set(layout.projectDirectory.file("external/jacoco.xml"))
    xmlReportTask.set("mergeJacocoReports")
}
```

Without `xmlReportTask` set in the override branch, no producer is known, no stamp is written, and the guard falls back to after-read-only detection — see the `koverGateDoctor` output below for how to tell which mode a module is in.

### `koverGateDoctor`

For a one-command snapshot of everything that decides a verdict, run:

```bash
./gradlew :<module>:koverGateDoctor
```

It prints the configured thresholds (and which metrics are disabled), the Kover XML the gate will read, whether that XML exists and is stale, and the packages excluded via `attributeCoverageFrom` — without running tests or failing the build:

```
koverGate doctor for :your-module

Thresholds:
  LINE: 100% (enabled)
  BRANCH: 100% (enabled)
  INSTRUCTION: 100% (enabled)
  METHOD: 100% (enabled)
  CLASS: 100% (enabled)

Kover XML: /abs/path/.../report.xml
  exists: true
  modified: 2026-06-18T14:03:11Z
  newest build script: 2026-06-17T09:12:44Z
  stale: false
  Report-contention guard: FULL

Excluded packages (attributeCoverageFrom):
  (none)
```

When no producer task is known (the `xmlReportFile` override branch with `xmlReportTask` left blank), that last line instead reads:

```
  Report-contention guard: PARTIAL (no producer task known; set xmlReportTask to restore full detection)
```

### `koverGateBump`

To ratchet a module's coverage thresholds up as it improves, run:

```bash
./gradlew :<module>:koverGateBump
```

It reads the module's current gap report and prints the `koverGate {}` threshold lines worth changing — one
`min<Metric>Coverage.set(N)` line per metric, computed from measured coverage and annotated with its prior value — for
you to merge into your existing block:

```
# koverGate: suggested threshold updates for :your-module
# Merge these lines into your existing koverGate { } block:

  minLineCoverage.set(92)     // was 85
  minBranchCoverage.set(88)   // was 80
```

It emits only these threshold lines, never a whole `koverGate {}` block — so it can never clobber the rest of your
configuration (such as `xmlReportFile` or `attributeCoverageFrom`). It does not edit your build scripts; you paste the
lines yourself.

Each suggested threshold is `floor(coverage) - margin`, clamped to `0..100`, so the suggested gate always keeps headroom
and cannot fail against the very report that produced it. By default the task only suggests **raising** a threshold
(ratcheting up); a metric whose coverage dropped is left alone. Two options adjust this:

- `--allow-lower` also emits suggestions that **lower** a threshold. Without it, the task reports how many reductions it
  withheld, so lowering a gate stays a deliberate act.
- `--margin=<n>` subtracts `n` extra percentage points from every suggestion, trading some ratchet strictness for
  resilience against coverage jitter across compiler or dependency bumps. For example, `--margin=2` on 92% coverage
  suggests `90`.

Metrics disabled via `disabledMetrics` are skipped (the gap report omits them). The task never fails the build.

## Cross-Module Coverage Attribution

Sometimes one module's tests exercise another module's code — the common
"acceptance / conformance / integration module tests the library" shape. By
default Kover counts those tests only against the module that *runs* them, so
the library module's gate sees its own code as uncovered.

`attributeCoverageFrom` makes the library module's gate count the other
module's coverage:

```kotlin
// library module's build.gradle.kts
koverGate {
    attributeCoverageFrom(
        ":library-conformance",
        excludePackages = listOf("com.example.conformance"),
    )
}
```

This wires Kover's cross-module aggregation so `:library-conformance`'s
coverage is merged into this module's report, and excludes the listed
packages — the conformance module's *own* classes — so they are not judged
against this module's thresholds (they have their own gate).

Notes:

- Requires the Kover plugin applied to the module declaring it.
- No dependency cycle is created even when `:library-conformance` depends on
  this module: Kover aggregation consumes coverage artifacts, not a
  compile/runtime dependency.
- Call it more than once to attribute coverage from several modules.
- `excludePackages` defaults to empty; omit it only if you want the
  contributing module's classes counted against this module's gate too.

## Excluding Classes from Coverage

Some classes should never count against a module's gate — generated code, test fixtures shipped
in `main`, or other artifacts your own tests are not meant to exercise. `excludes {}` filters them
out **before** coverage is computed, so both the pass/fail verdict and the gap report reflect only
the classes you actually want gated:

```kotlin
koverGate {
    excludes {
        classes("*.Fixture*", "com.example.generated.BuildConfig")
        packages("com.example.generated")
    }
}
```

- `classes(vararg String)` / `classes(Iterable<String>)` take one or more class-name patterns.
- `packages(vararg String)` / `packages(Iterable<String>)` exclude every class in the named
  package (and its sub-packages) — `packages("com.example.generated")` is shorthand for
  `classes("com.example.generated.*")`.

Patterns are matched against a class's fully-qualified dotted name (e.g. `com.example.Widget`),
using the same wildcard rules as Kover itself:

| Symbol | Meaning |
|---|---|
| `*` | any characters, **including dots** — crosses package boundaries |
| `?` | exactly one arbitrary character |
| `#` | any characters **except** dots — stays within one package segment |

The pattern must match the entire fully-qualified name (not just a substring).

**Copying from `kover {}`:** if you already have a `kover { reports { excludes { ... } } }` block,
you can copy its `classes(...)` and `packages(...)` lines into `koverGate { excludes { ... } }`
almost verbatim — same method names, same wildcard semantics. The one exception is
`annotatedBy(...)`: koverGate's XML-based report has no per-class annotation data, so
`annotatedBy(...)` is not supported inside `koverGate { excludes { } }`. Leave any `annotatedBy(...)`
lines in the `kover {}` block; if you accidentally paste one into `koverGate { excludes { } }`, the
build fails at script-compilation time with an "unresolved reference" error rather than silently
ignoring it.

Unlike `kover {}`'s own excludes, `koverGate`'s `excludes {}` is applied by koverGate itself while
reading the Kover XML — it does not depend on Kover's excludes surviving into the report, so it
works even under the Gradle configuration cache and even when the report comes from an external
`xmlReportFile` (see [Android: Merging Unit + Instrumented Coverage](#android-merging-unit--instrumented-coverage)).

## Teaching Your Agent to Use koverGate

koverGate ships with a ready-made skill that tells AI coding agents how to run the coverage gate, read the JSON report, and write targeted tests to close the gaps.

The skill is at [`skills/kover-gate-report/SKILL.md`](skills/kover-gate-report/SKILL.md) in this repository.

### Adding the Skill to Claude Code

Claude Code discovers skills placed in `.claude/skills/<skill-name>/SKILL.md` within your project. Copy the skill directory there (create `.claude/skills/` if it does not exist):

```bash
# From your project root
mkdir -p .claude/skills/kover-gate-report
cp path/to/kovergate/skills/kover-gate-report/SKILL.md .claude/skills/kover-gate-report/SKILL.md
```

Once the file is in place, the skill is available in Claude Code as:

```
/kover-gate-report
```

You can invoke it directly, or instruct Claude to run it after writing or modifying tests in a module.

### Adding the Skill to Other Agents

Any agent that supports file-based skill definitions can use the same file. Copy `skills/kover-gate-report/SKILL.md` to wherever your agent looks for skill definitions and follow that agent's conventions for invocation.

If your agent does not have a skill system, paste the contents of the file into your agent's system prompt or project instructions.

## License

[Apache License 2.0](LICENSE)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines before submitting changes.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for a history of notable changes.
