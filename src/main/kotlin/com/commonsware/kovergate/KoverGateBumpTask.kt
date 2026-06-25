/*
 * Copyright 2026 Mark L. Murphy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.commonsware.kovergate

import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

// ─── Pure suggestion logic (unit-testable without Gradle) ───────────────────

private const val MIN_THRESHOLD = 0
private const val MAX_THRESHOLD = 100

/**
 * A single metric's current configured threshold and the new threshold suggested from its measured coverage.
 *
 * @property metric the coverage metric this suggestion applies to
 * @property current the threshold currently configured for the metric
 * @property suggested the new threshold computed from measured coverage
 */
internal data class GateSuggestion(val metric: CoverageMetric, val current: Int, val suggested: Int)

/**
 * Outcome of computing threshold suggestions for one module: the changes worth emitting and how many threshold
 * reductions were withheld because `--allow-lower` was not supplied.
 *
 * @property emitted the per-metric suggestions to print, in [CoverageMetric] declaration order
 * @property suppressedReductions the count of metrics whose suggestion would lower the threshold but were withheld
 */
internal data class SuggestionOutcome(val emitted: List<GateSuggestion>, val suppressedReductions: Int)

/**
 * Parses the `--margin` option value into headroom percentage points. A negative integer is accepted and its absolute
 * value used; a value that is not an integer is rejected.
 *
 * @param raw the raw option string
 * @return the margin to apply (the absolute value of the parsed integer)
 * @throws GradleException if [raw] is not an integer
 */
internal fun parseMargin(raw: String): Int =
  raw.toIntOrNull()?.absoluteValue ?: throw GradleException("KoverGate: --margin must be an integer (got '$raw')")

/**
 * Computes per-metric threshold suggestions from a parsed gap report. For each metric present in the report, the
 * suggested threshold is `floor(percent) - margin`, clamped to `0..100`. A metric is emitted only when its suggestion
 * differs from the current threshold; a suggestion that would lower the threshold is emitted only when [allowLower] is
 * true, and is otherwise counted in [SuggestionOutcome.suppressedReductions]. Metrics absent from the report — for
 * example those disabled via `disabledMetrics`, which the report omits — are skipped.
 *
 * @param report the parsed gap report supplying measured coverage and current thresholds
 * @param margin percentage points of headroom subtracted from each measured coverage floor
 * @param allowLower whether suggestions that lower a threshold should be emitted rather than withheld
 * @return the suggestions to emit and the count of withheld reductions
 */
internal fun computeSuggestions(report: GapReport, margin: Int, allowLower: Boolean): SuggestionOutcome {
  val emitted = mutableListOf<GateSuggestion>()
  var suppressedReductions = 0

  for (metric in CoverageMetric.entries) {
    val metricJson = report.metrics[metric.name] ?: continue
    val suggested = (floor(metricJson.percent).toInt() - margin).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    val current = metricJson.threshold

    if (suggested != current) {
      if (suggested > current || allowLower) {
        emitted += GateSuggestion(metric, current, suggested)
      } else {
        suppressedReductions++
      }
    }
  }

  return SuggestionOutcome(emitted, suppressedReductions)
}

/**
 * Maps a coverage metric to the `koverGate {}` DSL property a developer sets to configure its threshold.
 *
 * @param metric the coverage metric
 * @return the DSL property name (e.g. `minLineCoverage`)
 */
private fun thresholdPropertyName(metric: CoverageMetric): String =
  when (metric) {
    CoverageMetric.LINE -> "minLineCoverage"
    CoverageMetric.BRANCH -> "minBranchCoverage"
    CoverageMetric.INSTRUCTION -> "minInstructionCoverage"
    CoverageMetric.METHOD -> "minMethodCoverage"
    CoverageMetric.CLASS -> "minClassCoverage"
  }

/**
 * Renders the DSL setter call for a single suggestion, e.g. `minLineCoverage.set(92)`.
 *
 * @param suggestion the suggestion to render
 * @return the setter-call source fragment
 */
private fun renderSetCall(suggestion: GateSuggestion): String =
  "${thresholdPropertyName(suggestion.metric)}.set(${suggestion.suggested})"

/**
 * Renders the console snippet for a module's suggestions: the bare `min<Metric>Coverage.set(N)` lines to merge into an
 * existing `koverGate {}` block, each annotated with its prior value. Emits a short notice when nothing changed, and a
 * hint about `--allow-lower` whenever threshold reductions were withheld.
 *
 * @param projectPath the Gradle project path (e.g. `:app`) the suggestions are for
 * @param outcome the computed suggestions and withheld-reduction count
 * @return the multi-line snippet to print
 */
internal fun renderSuggestions(projectPath: String, outcome: SuggestionOutcome): String = buildString {
  appendLine("# koverGate: suggested threshold updates for $projectPath")

  if (outcome.emitted.isEmpty()) {
    appendLine("# No threshold changes suggested.")
  } else {
    appendLine("# Merge these lines into your existing koverGate { } block:")
    appendLine()

    val callWidth = outcome.emitted.maxOf { renderSetCall(it).length }

    for (suggestion in outcome.emitted) {
      appendLine("  ${renderSetCall(suggestion).padEnd(callWidth)}  // was ${suggestion.current}")
    }
  }

  if (outcome.suppressedReductions > 0) {
    appendLine()
    append(
      "# ${outcome.suppressedReductions} metric(s) would lower a threshold; re-run with --allow-lower to emit them."
    )
  }
}

// ─── The Gradle task ────────────────────────────────────────────────────────

/**
 * Diagnostic Gradle task that bumps a module's `koverGate {}` coverage thresholds toward its current measured coverage.
 * For each enabled metric it prints a `min<Metric>Coverage.set(N)` line — derived from the current gap report — to
 * merge into the module's existing block. By default it only suggests raising thresholds (ratcheting coverage up); pass
 * `--allow-lower` to also suggest reductions, and `--margin` to leave headroom. It never edits build scripts and never
 * fails the build.
 */
@DisableCachingByDefault(because = "Diagnostic task that always reports current suggestions")
public abstract class KoverGateBumpTask : DefaultTask() {
  /** The gap-report JSON produced by `koverGateGenerateReport`, read for measured coverage and current thresholds. */
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) public abstract val gapReportFile: RegularFileProperty

  /** Gradle project path (e.g., `:app`), set by the plugin from [org.gradle.api.Project.getPath]. */
  @get:Internal public abstract val projectPath: Property<String>

  /**
   * Percentage-point headroom subtracted from each metric's measured coverage floor when computing its suggested
   * threshold. Supplied on the command line via `--margin`; defaults to `"0"`. Parsed as an integer; a negative value
   * is treated as its absolute value.
   */
  @get:Input
  @get:Option(
    option = "margin",
    description = "Percentage-point headroom subtracted from each suggested threshold (default 0)",
  )
  public abstract val margin: Property<String>

  /**
   * Whether to also emit suggestions that would lower an existing threshold. Supplied on the command line via
   * `--allow-lower`; defaults to `false`, so the task only suggests raising thresholds.
   */
  @get:Input
  @get:Option(option = "allow-lower", description = "Also emit suggestions that lower an existing threshold")
  public abstract val allowLower: Property<Boolean>

  /** Reads the gap report, computes threshold suggestions, and prints them. Never fails the build. */
  @TaskAction
  internal fun bump() {
    val marginValue = parseMargin(margin.get())
    val report = Json.decodeFromString<GapReport>(gapReportFile.get().asFile.readText())
    val outcome = computeSuggestions(report, marginValue, allowLower.get())
    val output = renderSuggestions(projectPath.get(), outcome)

    logger.lifecycle(output)
  }
}
