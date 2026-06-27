---
name: kover-gate-report
description: Run KoverGate coverage gate and fix coverage gaps after writing or modifying tests
---

# koverGate Report Skill

After writing or modifying tests in a module, check coverage gaps and close them.

## Single-Module Usage

### Steps

1. **Run the coverage gate task** for the module you changed:
   ```bash
   ./gradlew :<module-name>:koverGateReport
   ```
   Replace `<module-name>` with the Gradle module path (e.g., `:app`, `:core:data`).

2. **Read the JSON gap report:**
   ```
   <module-name>/build/reports/kover/gap-report.json
   ```

3. **Interpret results:**
   - If `"passed": true` — coverage requirements are met. Done.
   - If `"passed": false` — examine the `files` array. Each entry lists:
     - `uncoveredLines`: line numbers with **zero** covered instructions (`ci == 0`) — genuinely never executed; write tests for these
     - `partiallyCoveredLines`: line numbers with both covered and missed instructions (`ci > 0 && mi > 0`) — mostly exercised; the residual is frequently synthetic duplicate bytecode (inlined lambdas, coroutine state machines) that no ordinary test can close. Verify against the source before writing tests
     - `missedBranchLines`: per-line `{ line, missed, total, fullyInstructionCovered }` objects for lines with partial branch coverage (`mb > 0`). A `missed`/`total` of `1/2` often marks an unreachable arm (refactor or accept); `2/2` is a genuinely untested branch (write a test). `fullyInstructionCovered: true` means every instruction on the line is covered and only a branch arm is missed — a strong hint the arm is synthetic or unreachable

4. **Write targeted tests** to cover the identified lines and branches. Focus on:
   - The specific line numbers listed in `uncoveredLines`
   - The conditional paths at lines listed in `missedBranchLines`

5. **Re-run the task** to confirm gaps are closed:
   ```bash
   ./gradlew :<module-name>:koverGateReport
   ```
   Repeat until `"passed": true`.

## Multi-Module Usage

When `com.commonsware.kovergate` is applied to the root project, running `koverGateReport` at root
generates an aggregate report that merges all submodule reports.

1. **Run the root coverage gate:**
   ```bash
   ./gradlew koverGateReport
   ```
   This runs every submodule's `koverGateReport` task, then writes the aggregate report.

2. **Read the aggregate JSON report:**
   ```
   build/reports/kover/gap-report.json
   ```
   The root report has a `modules` array — one `GapReport` per submodule — and a top-level `passed`
   field that is `true` only when every module passed.

3. **If the build failed** (a submodule failed its gate), the aggregate report is not generated.
   Check each submodule's individual report at `<module>/build/reports/kover/gap-report.json`.
   To collect results even when some modules fail, use:
   ```bash
   ./gradlew koverGateReport --continue
   ```
   Then read per-module reports from whichever modules completed successfully.

4. **Fix gaps module by module** using the same process as single-module usage above.

## Merged Unit + Instrumented Coverage (Android)

When a module's `koverGate {}` block sets `xmlReportFile` pointing to a `JacocoReport` merge task,
the gap report reflects coverage from **both** unit tests (`test/`) and Android instrumented tests
(`androidTest/`) combined. The exec data is merged at the JaCoCo execution-data level before the
XML is produced, so the parser sees a single unified view.

### Scenario 2: Kover + AGP merged (recommended for Android libraries)

Apply Kover with `useJacoco()` so it emits JaCoCo-compatible exec data, then merge with AGP's
instrumented-test exec using a `JacocoReport` task:

```kotlin
plugins {
    id("com.android.library") version "8.11.2"
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.commonsware.kovergate") version "0.5.0"
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

// KoverGate automatically disables Gradle jacoco plugin Test instrumentation
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

### Scenario 3: AGP-only (no Kover)

When Kover is not applied at all, the Gradle `jacoco` plugin handles unit-test exec and AGP handles
instrumented-test exec:

```kotlin
plugins {
    id("com.android.library") version "8.11.2"
    id("org.jetbrains.kotlin.android")
    id("com.commonsware.kovergate") version "0.5.0"
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

**Interpreting gaps in a merged report:**

- A line in `uncoveredLines` means **neither** test type covers it — not just one.
- A line in `partiallyCoveredLines` means the union of both suites covers some but not all of the line's instructions — often a synthetic duplicate mapping rather than a real gap.
- A line in `missedBranchLines` means a branch at that line is missed by the union of both suites.
- The fix may be a unit test *or* an instrumented test depending on what the code path needs:
  - Pure Kotlin/JVM logic → add a unit test in `src/test/`.
  - Code that calls Android framework APIs (`Context`, `Activity`, `View`, etc.) or requires a
    real device/emulator → add an instrumented test in `src/androidTest/`.

**Running the gate when `xmlReportFile` is set:**

`koverGateReport` infers a dependency on the `JacocoReport` merge task through the `TaskProvider`
used to wire `xmlReportFile`. Running `koverGateReport` alone is sufficient — it triggers
`mergedCoverage`, which in turn triggers `testDebugUnitTest` and `createDebugCoverageReport`
(the latter runs connected tests on the device). A connected device or emulator must be attached.

```bash
./gradlew :<module>:koverGateReport
```

**Kover optional:** when `xmlReportFile` is set, the Kover plugin need not be applied. The AGP-only
scenario (no Kover, exec from the Gradle `jacoco` plugin + AGP instrumented) is fully supported.

## Scoped Report (Changed Lines Only)

When you only need to cover the lines **you changed on the current branch**, use the scoped report instead of (or after) the full gap report. This is the fastest way for an agent to triage only the code it wrote.

### Single-Module Scoped Workflow

1. **Run the scoped report task:**
   ```bash
   ./gradlew :<module-name>:koverGateScopedReport
   ```
   This automatically runs `koverGateGenerateReport` first (which parses the Kover XML and writes the gap report JSON without gating the build), then filters its output to only the lines changed on the current branch relative to `main`. Committed **and** uncommitted tracked changes are included. Because it does not depend on `koverGateReport`, it always runs — even when coverage is below threshold.

2. **Read the scoped JSON report:**
   ```
   <module-name>/build/reports/kover/scoped-gap-report.json
   ```
   Same shape as `gap-report.json` but `metrics` is empty and `files` contains only entries where at least one changed line is uncovered or has a missed branch.

3. **Interpret results:**
   - `"passed": true` — no in-scope gaps. The lines you changed are fully covered.
   - `"passed": false` — examine `files` as usual; only in-scope lines appear.

4. **Write targeted tests** for the listed lines, re-run, and repeat until `passed: true`.

> **Note:** `koverGateScopedReport` is a reporting task that does not fail the build on gaps. Because it depends on `koverGateGenerateReport` (not on `koverGateReport`), it always completes — even when overall coverage is below threshold. The build gate remains `koverGateReport`.

**Override the base ref** if your branch diverges from something other than `main`:
```bash
./gradlew :<module-name>:koverGateScopedReport --base=develop
```

### Multi-Module Scoped Workflow

```bash
./gradlew koverGateScopedReport -PkoverGateBase=main
```

Running at the root runs every submodule's `koverGateScopedReport` task (each diffs against the given base), then writes an aggregate scoped report:
```
build/reports/kover/scoped-gap-report.json
```
The aggregate is an `AggregateGapReport` with a `modules` array — one `GapReport` per submodule — and a top-level `passed` that is `true` only when every module has no in-scope gaps.

Use `-PkoverGateBase=<ref>` (not `--base`) for multi-module runs — `--base` is a per-task option and does not propagate to dependency tasks.

### Scoped Report JSON Shape

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

Only `uncoveredLines` and `missedBranchLines` that fall on a changed line appear. Lines that were already uncovered before your branch are excluded.

> **Limitation:** Untracked files (brand-new files never added with `git add`) are not scoped in until staged. Run `git add <file>` before the task if you need new files included.

## Notes

- Adjust thresholds in your module's `build.gradle.kts` via the `koverGate {}` block if 100% is not the right target for a specific metric.
- Disable metrics that don't apply with `disabledMetrics.set(setOf(CoverageMetric.BRANCH))`.
- The JSON report is always written (even on pass) at `build/reports/kover/gap-report.json` for each leaf module.
- The aggregate report at root is only written when all submodule tasks succeed.

## Attributed coverage (cross-module)

When a module declares `koverGate { attributeCoverageFrom(":other", ...) }`, its gap report already reflects
`:other`'s tests merged in, and `:other`'s own classes are excluded from this module's report. So a remaining
gap means *neither* this module's own tests *nor* the attributed module's tests cover that line. Close it by
adding a test in whichever module is the natural home for exercising that code path — often the conformance /
acceptance module, not the library module itself.

## Diagnosing a Wrong or Stale Result

If a gate result looks wrong — for example, it reports lines uncovered that your tests clearly exercise, or a
package you excluded still appears — suspect a **stale** Kover XML before writing new tests.

1. **Check the `source` block** in `gap-report.json`. If `"stale": true`, the XML the report was computed from
   predates your build configuration.

2. **Run the doctor** for a full snapshot of what the gate will read:
   ```bash
   ./gradlew :<module>:koverGateDoctor
   ```
   It prints the thresholds, the Kover XML path, whether that XML exists and is `stale`, and the excluded
   packages — without running tests or failing the build.

3. **If the report is stale, regenerate coverage** before drawing conclusions:
   ```bash
   ./gradlew :<module>:koverGateReport --rerun-tasks
   ```
   Then re-read the gap report. Only write new tests once the report is fresh — otherwise you may be chasing a
   phantom gap that fresh coverage already closes.

## Upgrade Guides

Historical migration notes, oldest first. Each guide is self-contained — jump to the one matching the version
you are coming from.

### Adding Root Project Support (0.1.3 → 0.2.0)

Prior to 0.2.0, KoverGate only supported leaf modules and produced no aggregate report at root.

To enable the aggregate report for an existing multi-module project:

1. **Bump the plugin version** in all modules from `0.1.3` to `0.2.0`.

2. **Apply the plugin to the root project.** Add to your root `build.gradle.kts`:
   ```kotlin
   plugins {
       id("com.commonsware.kovergate") version "0.2.0"
   }
   ```
   The root project does **not** need `org.jetbrains.kotlinx.kover` applied.

3. **Run `./gradlew koverGateReport`** from the project root to verify the aggregate report is
   generated at `build/reports/kover/gap-report.json`.

### Scoped Report and JaCoCo Support (0.2.0 → 0.3.0-alpha01)

0.3.0-alpha01 adds two new features. No breaking changes; existing configuration continues to work without modification.

#### New: `koverGateScopedReport` task

Available automatically on every module that already applies `com.commonsware.kovergate`. No configuration required to start using it:

```bash
./gradlew :<module>:koverGateScopedReport
```

See the **Scoped Report** section above for full usage.

#### New: `xmlReportFile` DSL property

Allows gating on an externally produced JaCoCo XML report (e.g. from a `JacocoReport` merge task) instead of a Kover-generated report. When set, the Kover plugin is optional. See **Merged Unit + Instrumented Coverage (Android)** above for setup.

To upgrade: bump the plugin version to `0.3.0-alpha01` in every module and the root project (if applicable). No other changes are required unless you want to opt in to the new features.

### Split Generate/Gate Tasks (0.3.0-alpha01 → 0.3.0)

0.3.0 adds two behaviour changes that improve developer experience. No breaking changes; existing configuration continues to work without modification.

#### New: `koverGateGenerateReport` task

Leaf modules now expose a `koverGateGenerateReport` task that parses the Kover XML and writes the gap report JSON **without failing the build**. The existing `koverGateReport` task is now a thin gate that reads the JSON produced by `koverGateGenerateReport` and fails the build only when `passed == false`.

The `koverGateGenerateReport` task name is the one to reference in your own task wiring if you need the JSON without triggering the gate (for example, when passing it to an external reporting tool).

#### Changed: `koverGateScopedReport` dependency

`koverGateScopedReport` now depends on `koverGateGenerateReport` (the pure JSON writer) instead of `koverGateReport` (the gate). This means running `koverGateScopedReport` **always succeeds** — even when overall coverage is below threshold — without needing `--continue`.

#### New: Auto-disable of Gradle jacoco instrumentation

When `kover { useJacoco() }` is detected, KoverGate now automatically disables the Gradle `jacoco` plugin's instrumentation on `Test` tasks. The manual workaround block previously shown in the Scenario 2 setup is no longer required:

```kotlin
// No longer needed in 0.3.0+:
// tasks.withType<Test>().configureEach {
//     extensions.getByType(JacocoTaskExtension::class.java).isEnabled = false
// }
```

To upgrade: bump the plugin version to `0.3.0` in every module and the root project (if applicable). No other changes are required.

### Per-Line Branch Ratios, Doctor & Freshness (0.3.0 → 0.4.0)

0.4.0 adds two diagnostics and makes one **breaking** change to the gap report JSON.

#### Breaking (JSON): `missedBranchLines` is now an array of objects

`missedBranchLines` — in both `gap-report.json` and `scoped-gap-report.json` — changed from an array of bare line numbers to an array of `{ line, missed, total }` objects.

Before (0.3.0 and earlier):

```json
"missedBranchLines": [42]
```

After (0.4.0):

```json
"missedBranchLines": [{ "line": 42, "missed": 1, "total": 2 }]
```

The extra detail lets you triage at a glance: a `missed`/`total` of `1/2` usually marks one unreachable branch arm (refactor or accept), while `2/2` is a genuinely untested branch (write a test). **Any agent or script that parsed the old integer array must be updated** to read the `line` field of each object.

#### New: `koverGateDoctor` task

Leaf modules expose a `koverGateDoctor` task that prints, in one place, the configured thresholds (and which metrics are disabled), the Kover XML the gate will read, whether that XML exists and is stale, and the packages excluded via `attributeCoverageFrom` — without running tests or failing the build:

```bash
./gradlew :<module>:koverGateDoctor
```

Use it as a first step when a verdict looks wrong. See **Diagnosing a Wrong or Stale Result** above.

#### New: report provenance and staleness warning

`gap-report.json` now carries a `source` block recording the XML path the numbers came from, its last-modified time, the newest build-script modification time, and a `stale` flag. When the XML predates the module's build scripts, `koverGateGenerateReport` logs a freshness warning (it never fails the build) so a stale report cannot quietly produce a confident-but-wrong verdict.

To upgrade: bump the plugin version to `0.4.0` in every module and the root project (if applicable). The only required change is on the consumer side, if anything you maintain parses `missedBranchLines` from the JSON.

### Producer-Reuse Freshness Warning (Unreleased)

Two refinements to staleness reporting:

- The build-script staleness check is now explicitly labeled a **build-script timestamp heuristic** in both the `koverGateGenerateReport` warning and `koverGateDoctor` output. It compares the Kover XML's mtime to your build scripts' mtimes — a coarse proxy, not a guarantee.
- A new **producer-reuse warning** fires when a module's `koverGateReport` gate runs (executes or fails) while Gradle served that module's `koverXmlReport*` task up-to-date or from cache. It is logged at build completion: `KoverGate freshness: the Kover XML for <module> was reused (UP_TO_DATE/FROM_CACHE) this build while its coverage gate ran; ... re-run with --rerun-tasks ...`. This catches the case where a verdict looks wrong because Gradle did not regenerate the Kover XML. Like the other freshness signals, it never fails the build.

- Aggregate root projects now receive a minimal `RootCoverageGateExtension`. If you previously had threshold
  properties (`minLineCoverage`, etc.) in `koverGate {}` at the root, they were silently ignored; now they produce a
  compile error. Remove them from the root `koverGate {}` block — thresholds are enforced per leaf module only.

No action needed on upgrade for the freshness signals; both are diagnostic-only. If you had threshold properties in a
root `koverGate {}` block, remove them.

## Ratcheting Thresholds Up (`koverGateBump`)

After you close gaps and a module's coverage rises above its configured thresholds, lock the gains in by raising the
thresholds. The `koverGateBump` task does the arithmetic for you:

```bash
./gradlew :<module-name>:koverGateBump
```

It reads the module's current `gap-report.json` and prints the `min<Metric>Coverage.set(N)` lines to merge into the
module's existing `koverGate {}` block — one per metric whose threshold should change, each annotated with its prior
value. Interpreting and using the output:

- Each suggestion is `floor(coverage) - margin`, clamped to `0..100`. By default the task only suggests **raising**
  thresholds; a metric whose coverage fell is left untouched.
- Paste only the printed `min<Metric>Coverage.set(N)` lines into the existing block. The task never emits a whole
  `koverGate {}` block and never edits build scripts, so it cannot disturb other settings (e.g. `xmlReportFile`,
  `attributeCoverageFrom`).
- Pass `--allow-lower` to also emit suggestions that lower a threshold (use deliberately — this relaxes the gate).
- Pass `--margin=<n>` to leave `n` percentage points of headroom below measured coverage, reducing flakiness from
  coverage jitter.
- Metrics disabled via `disabledMetrics` are omitted from the report and therefore never suggested.

This task never fails the build; it only prints suggestions.
