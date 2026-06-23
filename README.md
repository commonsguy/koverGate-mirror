# KoverGate

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
    id("com.commonsware.kovergate") version "0.4.0"
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

For Android projects, Kover generates build-variant-specific XML report tasks (`koverXmlReportDebug`, `koverXmlReportRelease`, etc.) rather than the `koverXmlReportJvm` task used by JVM modules. Set `xmlReportTask` to tell KoverGate which variant to gate on:

```kotlin
koverGate {
    xmlReportTask.set("koverXmlReportDebug")
}
```

If you forget to set this and no auto-detectable task exists, the build will fail with a message listing the available `koverXmlReport*` tasks to help you choose the right name.

### Android: Merging Unit + Instrumented Coverage

Kover alone cannot see coverage from `androidTest/` instrumented tests — it only instruments JVM-based test tasks. AGP can produce JaCoCo coverage data for connected tests, and merging both at the execution-data level gives a complete picture: a line covered by *either* test type counts as covered.

KoverGate supports this via the `xmlReportFile` property. When set, KoverGate reads coverage from that file instead of auto-detecting a Kover task, and the Kover plugin becomes fully optional.

#### Scenario 1: Kover only (unit tests)

Kover instruments only JVM-based test tasks. Instrumented tests (`androidTest/`) are not included in coverage. This is the same setup shown above in [Android Projects](#android-projects):

```kotlin
plugins {
    id("com.android.library") version "8.11.2"
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.commonsware.kovergate") version "0.4.0"
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
    id("com.commonsware.kovergate") version "0.4.0"
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

#### Scenario 3: AGP-only (no Kover)

When Kover is not applied at all, the Gradle `jacoco` plugin handles unit-test exec and AGP handles instrumented-test exec:

```kotlin
plugins {
    id("com.android.library") version "8.11.2"
    id("org.jetbrains.kotlin.android")
    id("com.commonsware.kovergate") version "0.4.0"
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
    id("com.commonsware.kovergate") version "0.4.0"
}
```

The root `koverGate {}` block only exposes `prettyPrintJson` — threshold properties such as `minLineCoverage` are not
available at the root and produce a compile-time error if attempted. Coverage thresholds are enforced per leaf module.
If you do not need pretty-printed aggregate JSON, you can omit `koverGate {}` at the root entirely.

**Each submodule `build.gradle.kts`:**
```kotlin
plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.commonsware.kovergate") version "0.4.0"
}
```

When the root project has subprojects, KoverGate registers an aggregate `koverGateReport` task at root that depends on all submodule `koverGateReport` tasks. Running:

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

Files with gaps:
  Foo.kt
    Lines 42, 57 uncovered
    Branches 42 (1/2) missed (1 of 2)
  Bar.kt
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
      "partiallyCoveredLines": [88]
    },
    {
      "name": "Bar.kt",
      "package": "com/example",
      "uncoveredLines": [113],
      "missedBranchLines": [],
      "partiallyCoveredLines": []
    }
  ]
}
```

`uncoveredLines` lists only lines with **zero** covered instructions — genuinely never executed. A line that is partially covered (some instructions covered, some missed) appears in `partiallyCoveredLines` instead; its residual is frequently synthetic duplicate bytecode (inlined lambdas, coroutine state machines) that no ordinary test can close. On each `missedBranchLines` entry, `fullyInstructionCovered: true` flags a line whose instructions are all covered and only a branch arm is missed — usually a synthetic or unreachable arm.

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

`koverGateScopedReport` depends on `koverGateGenerateReport` (the non-gating JSON writer) and reads its `gap-report.json`. It then diffs the working tree against the merge-base of the configured base ref and `HEAD` — capturing committed *and* uncommitted tracked changes — and keeps only the `uncoveredLines` and `missedBranchLines` entries that fall on a changed line. The result is written to `build/reports/kover/scoped-gap-report.json`. Because it depends on the generate step rather than the gate, it always completes — even when overall coverage is below threshold.

> **Note:** Untracked files (never `git add`-ed) are excluded from the diff. Run `git add` first if you need new files included.

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
    "newestBuildScriptModified": "2026-06-17T09:12:44Z"
  }
}
```

- `xmlPath` — the Kover (or JaCoCo) XML the report was computed from.
- `xmlModified` / `newestBuildScriptModified` — ISO-8601 UTC timestamps.
- `stale` — `true` when the newest build script is newer than the XML, i.e. the on-disk coverage may predate your current build configuration.

### Freshness warning

When `stale` is `true`, `koverGateGenerateReport` logs a warning (it never fails the build):

```
KoverGate: coverage report may be STALE.
  Kover XML: /abs/path/.../report.xml
  XML modified:          2026-06-18T14:03:11Z
  Build script modified: 2026-06-19T08:00:00Z
  The XML predates your build configuration; re-run with --rerun-tasks to regenerate coverage.
```

This catches the case where a cached or up-to-date Kover XML predates a change to your coverage configuration (for example, adding an `excludes` filter) and would otherwise produce a confident but wrong verdict. The signal is a coarse heuristic — any build-script edit can trigger it — so it is a *warning*, never a gate failure.

> **Why always re-run?** `koverGateGenerateReport` (the cheap XML→JSON step) is intentionally **not cached** and always runs, so the freshness check is re-evaluated on every build. The gate itself (`koverGateReport`) still stays up-to-date when the JSON is unchanged, so this adds no meaningful cost.

### Producer-reuse warning

The `stale` flag above is a build-script **timestamp heuristic**: it only compares the Kover XML's modification time to your build scripts'. It cannot tell whether Gradle actually regenerated the XML this build.

KoverGate adds a second, complementary signal for exactly that. When a module's `koverGateReport` gate runs (executes or fails) while Gradle serves that module's `koverXmlReport*` task **up-to-date or from cache**, KoverGate logs a warning at the end of the build:

```
KoverGate freshness: the Kover XML for :app was reused (UP_TO_DATE) this build while its coverage gate ran; if the verdict looks wrong, re-run with --rerun-tasks to regenerate coverage.
```

This is the situation in which a verdict can look wrong because Gradle did not rebuild the Kover XML from fresh inputs. Like the staleness heuristic, it never fails the build — re-run with `--rerun-tasks` to force regeneration.

### `koverGateDoctor`

For a one-command snapshot of everything that decides a verdict, run:

```bash
./gradlew :<module>:koverGateDoctor
```

It prints the configured thresholds (and which metrics are disabled), the Kover XML the gate will read, whether that XML exists and is stale, and the packages excluded via `attributeCoverageFrom` — without running tests or failing the build:

```
KoverGate doctor for :your-module

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

Excluded packages (attributeCoverageFrom):
  (none)
```

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

## Teaching Your Agent to Use KoverGate

KoverGate ships with a ready-made skill that tells AI coding agents how to run the coverage gate, read the JSON report, and write targeted tests to close the gaps.

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
