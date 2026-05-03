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
     - `uncoveredLines`: line numbers with no coverage (`mi > 0`)
     - `missedBranchLines`: line numbers with partial branch coverage (`mb > 0`)

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

## Upgrade Guide: Adding Root Project Support (0.1.3 → 0.2.0)

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

## Notes

- Adjust thresholds in your module's `build.gradle.kts` via the `koverGate {}` block if 100% is not the right target for a specific metric.
- Disable metrics that don't apply with `disabledMetrics.set(setOf(CoverageMetric.BRANCH))`.
- The JSON report is always written (even on pass) at `build/reports/kover/gap-report.json` for each leaf module.
- The aggregate report at root is only written when all submodule tasks succeed.
