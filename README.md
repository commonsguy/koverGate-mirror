# KoverGate

A Gradle plugin that gates builds on [Kover](https://github.com/Kotlin/kotlinx-kover) coverage thresholds and generates structured gap reports. The objective is to make it easier for coding agents to process coverage results and identify where more tests are required.

When the `koverGateReport` task runs, it reads Kover's XML coverage report, checks each enabled metric against its configured minimum, and fails the build if any metric falls short. It always writes a JSON report to `build/reports/kover/gap-report.json` — even on success — so that both humans and automated agents can consistently parse the results.

## Requirements

- Gradle 8+
- Kotlin DSL (`build.gradle.kts`)
- [Kover Gradle plugin](https://github.com/Kotlin/kotlinx-kover) applied to the same module

## Adding the Plugin

The plugin is published to the Gradle Plugin Portal. Apply it alongside Kover in your module's `build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("com.commonsware.kovergate") version "0.1.1"
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

Add a `koverGate` block to your module's `build.gradle.kts`. All properties are optional; the defaults enforce 100% coverage on every metric.

```kotlin
koverGate {
    // Minimum percentage (0–100) required for each metric.
    // Defaults to 100 for all metrics.
    minLineCoverage.set(90)
    minBranchCoverage.set(80)
    minInstructionCoverage.set(90)
    minMethodCoverage.set(95)
    minClassCoverage.set(100)

    // Exclude specific metrics from enforcement entirely.
    // Excluded metrics are omitted from the JSON report as well.
    disabledMetrics.set(setOf(CoverageMetric.BRANCH))

    // Pretty-print the JSON gap report (useful for debugging).
    // Defaults to false.
    prettyPrintJson.set(true)

    // Kover XML report task to wire to. Required for Android projects — see below.
    // Defaults to auto-detecting koverXmlReportJvm or koverXmlReport.
    xmlReportTask.set("koverXmlReportDebug")
}
```

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

## Running the Task

```bash
./gradlew :your-module:koverGateReport
```

The task depends on Kover's XML report task automatically; there is no need to run Kover separately first.

### Console Output

On failure, the task prints a summary to the build log and then fails:

```
Coverage gate FAILED for :your-module
  Line:        88.5% (177/200) [FAIL]  [min: 90%]
  Branch:      91.2% ( 52/ 57) [PASS]
  Instruction: 89.1% (890/999) [FAIL]  [min: 90%]
  Method:      96.0% ( 48/ 50) [PASS]
  Class:       100.0% (12/12) [PASS]

Files with gaps:
  Foo.kt
    Lines 42, 57 uncovered
    Branches 42 missed (1 of 2)
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
      "missedBranchLines": [42]
    },
    {
      "name": "Bar.kt",
      "package": "com/example",
      "uncoveredLines": [113],
      "missedBranchLines": []
    }
  ]
}
```

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
