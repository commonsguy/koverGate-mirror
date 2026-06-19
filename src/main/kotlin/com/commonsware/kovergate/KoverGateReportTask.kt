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

import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

// ─── Pure data + functions (unit-testable without Gradle) ──────────────────

private const val PERCENT_SCALE = 100.0
private const val ONE_DECIMAL_SCALE = 10.0

/** Result of computing coverage for a single metric. */
internal data class MetricResult(
  /** Number of instrumented units that have test coverage. */
  val covered: Int,
  /** Total number of instrumented units (covered + missed). */
  val total: Int,
  /** Coverage percentage, rounded to 1 decimal place. */
  val percent: Double,
  /** Minimum required coverage percentage. */
  val threshold: Int,
  /** True if covered meets or exceeds the threshold. */
  val passed: Boolean,
)

/**
 * Computes coverage percentage from covered and missed counts. Returns `PERCENT_SCALE` when both counts are zero (no
 * lines to cover). Result is rounded to 1 decimal place.
 */
internal fun computePercent(covered: Int, missed: Int): Double {
  if (covered == 0 && missed == 0) return PERCENT_SCALE

  val total = covered + missed
  // Compute percentage and round to 1 decimal place
  return ((covered * PERCENT_SCALE / total) * ONE_DECIMAL_SCALE).roundToInt() / ONE_DECIMAL_SCALE
}

/** Creates a MetricResult by calculating percent and determining pass status. */
internal fun computeMetricResult(covered: Int, missed: Int, threshold: Int): MetricResult {
  val percent = computePercent(covered, missed)

  return MetricResult(
    covered = covered,
    total = covered + missed,
    percent = percent,
    threshold = threshold,
    passed = percent >= threshold,
  )
}

/**
 * Builds a human-readable console summary of coverage results. Shows passed/failed status for each metric with counts,
 * thresholds, and details about uncovered lines and missed branches per file.
 */
internal fun buildConsoleOutput(
  /** The Gradle project path (e.g., `:app`). */
  projectPath: String,
  /** Computed results for each coverage metric. */
  metricResults: Map<CoverageMetric, MetricResult>,
  /** Source files that have uncovered lines or missed branch lines. */
  filesWithGaps: List<FileCoverage>,
  /** True if the coverage gate passed overall. */
  passed: Boolean,
): String = buildString {
  val status = if (passed) "PASSED" else "FAILED"

  appendLine("Coverage gate $status for $projectPath")

  for ((metric, result) in metricResults) {
    val tag = if (result.passed) "[PASS]" else "[FAIL]"
    val minSuffix = if (!result.passed) "  [min: ${result.threshold}%]" else ""

    appendLine(
      "  ${metric.name.lowercase().replaceFirstChar { it.uppercase() }}: " +
        "${result.percent}% (${result.covered}/${result.total}) $tag$minSuffix"
    )
  }

  if (filesWithGaps.isNotEmpty()) {
    appendLine()
    appendLine("Files with gaps:")

    for (file in filesWithGaps) {
      appendLine("  ${file.name}")

      if (file.uncoveredLines.isNotEmpty()) {
        val plural = if (file.uncoveredLines.size == 1) "Line" else "Lines"
        appendLine("    $plural ${file.uncoveredLines.joinToString(", ")} uncovered")
      }

      if (file.missedBranchLines.isNotEmpty()) {
        val countSuffix = if (file.totalBranches > 0) " (${file.missedBranches} of ${file.totalBranches})" else ""
        appendLine("    Branches ${file.missedBranchLines.joinToString(", ")} missed$countSuffix")
      }
    }
  }
}

/**
 * Parameters for building JSON output.
 *
 * @param projectPath the Gradle project path (e.g., `:app`)
 * @param passed true if the coverage gate passed overall
 * @param metricResults computed results for each coverage metric
 * @param filesWithGaps source files that have uncovered lines or missed branch lines
 * @param prettyPrint whether to format the JSON output with indentation, defaults to `false`
 * @param source provenance of the source XML, or null to omit the `source` block from the report
 */
internal data class JsonOutputParams(
  val projectPath: String,
  val passed: Boolean,
  val metricResults: Map<CoverageMetric, MetricResult>,
  val filesWithGaps: List<FileCoverage>,
  val prettyPrint: Boolean = false,
  val source: SourceProvenance? = null,
)

/**
 * Builds a JSON-formatted gap report from coverage results. Converts metrics to MetricJson DTOs and includes only files
 * with gaps.
 *
 * @param params the parameters for building JSON output
 * @return the JSON-formatted gap report
 */
internal fun buildJsonOutput(params: JsonOutputParams): String {
  val projectPath = params.projectPath
  val passed = params.passed
  val metricResults = params.metricResults
  val filesWithGaps = params.filesWithGaps
  val prettyPrint = params.prettyPrint
  val source = params.source
  val json = Json { this.prettyPrint = prettyPrint }
  val report =
    GapReport(
      module = projectPath,
      passed = passed,
      metrics =
        metricResults.entries.associate { (metric, r) ->
          metric.name to MetricJson(r.covered, r.total, r.percent, r.threshold, r.passed)
        },
      files = filesWithGaps.map { FileGap(it.name, it.packageName, it.uncoveredLines, it.missedBranchLines) },
      source = source,
    )

  return json.encodeToString<GapReport>(report)
}

// ─── The Gradle task ────────────────────────────────────────────────────────

/**
 * Gradle task that validates Kotlin/Kotlin-JVM coverage against configured thresholds and reports gaps in both
 * human-readable and JSON formats.
 */
@DisableCachingByDefault(because = "Configured to always generate a fresh report and freshness provenance")
public abstract class KoverGateReportTask : DefaultTask() {
  /** The Kover XML report file input (path-sensitive, cacheable). */
  @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) public abstract val xmlReportFile: RegularFileProperty

  /** Minimum required line coverage percentage. Defaults to `100`. */
  @get:Input public abstract val minLineCoverage: Property<Int>

  /** Minimum required branch coverage percentage. Defaults to `100`. */
  @get:Input public abstract val minBranchCoverage: Property<Int>

  /** Minimum required instruction coverage percentage. Defaults to `100`. */
  @get:Input public abstract val minInstructionCoverage: Property<Int>

  /** Minimum required method coverage percentage. Defaults to `100`. */
  @get:Input public abstract val minMethodCoverage: Property<Int>

  /** Minimum required class coverage percentage. Defaults to `100`. */
  @get:Input public abstract val minClassCoverage: Property<Int>

  /** Metrics to exclude from coverage validation. Defaults to empty set. */
  @get:Input public abstract val disabledMetrics: SetProperty<CoverageMetric>

  /**
   * Whether to pretty-print the JSON gap report output.
   *
   * When `true`, the JSON is formatted with indentation for human readability. When `false` (the default), compact JSON
   * is emitted.
   */
  @get:Input public abstract val prettyPrintJson: Property<Boolean>

  /** Output path for the JSON gap report. Defaults to `build/reports/kover/gap-report.json`. */
  @get:OutputFile public abstract val jsonReportFile: RegularFileProperty

  /**
   * Gradle project path (e.g., `:app`), set by the plugin from [org.gradle.api.Project.getPath]. Used as the module
   * identifier in console and JSON output.
   */
  @get:Internal public abstract val projectPath: Property<String>

  /**
   * Build scripts whose modification times are compared against the Kover XML report's modification time to detect a
   * stale report. Wired by the plugin to the module's and root project's build scripts, the settings file, and the
   * version catalog. Declared `@Internal` because the task is configured to always run, so these files do not
   * participate in up-to-date checking.
   */
  @get:Internal public abstract val buildScripts: ConfigurableFileCollection

  @TaskAction
  public fun generate() {
    val xmlFile = xmlReportFile.get().asFile

    if (!xmlFile.exists()) {
      throw GradleException(
        "Kover XML report not found at ${xmlFile.absolutePath}. " +
          "Ensure tests were executed and the Kover XML report task ran successfully."
      )
    }

    val parseResult = XmlReportParser.parse(xmlFile)
    val disabled = disabledMetrics.get()
    val thresholds =
      mapOf(
        CoverageMetric.LINE to minLineCoverage.get(),
        CoverageMetric.BRANCH to minBranchCoverage.get(),
        CoverageMetric.INSTRUCTION to minInstructionCoverage.get(),
        CoverageMetric.METHOD to minMethodCoverage.get(),
        CoverageMetric.CLASS to minClassCoverage.get(),
      )

    val metricResults: Map<CoverageMetric, MetricResult> =
      CoverageMetric.entries
        .filter { it !in disabled }
        .associate { metric ->
          val counter = parseResult.moduleCounters[metric]
          val result =
            if (counter != null) {
              computeMetricResult(counter.covered, counter.missed, thresholds[metric]!!)
            } else {
              // Metric absent from XML (e.g., no branches in module) →
              // vacuously passes at 100%
              computeMetricResult(0, 0, thresholds[metric]!!)
            }
          metric to result
        }

    val overallPassed = metricResults.values.all { it.passed }
    val filesWithGaps = parseResult.files.filter { it.uncoveredLines.isNotEmpty() || it.missedBranchLines.isNotEmpty() }
    val buildScriptTimes = buildScripts.files.filter { it.exists() }.map { it.lastModified() }
    val provenance = computeProvenance(xmlFile.absolutePath, xmlFile.lastModified(), buildScriptTimes)

    // Write JSON (always, even on pass)
    val jsonFile = jsonReportFile.get().asFile

    jsonFile.parentFile.mkdirs()
    jsonFile.writeText(
      buildJsonOutput(
        JsonOutputParams(
          projectPath = projectPath.get(),
          passed = overallPassed,
          metricResults = metricResults,
          filesWithGaps = filesWithGaps,
          prettyPrint = prettyPrintJson.get(),
          source = provenance,
        )
      )
    )

    // Print console summary
    val summary = buildConsoleOutput(projectPath.get(), metricResults, filesWithGaps, overallPassed)

    logger.lifecycle(summary)

    val freshnessWarning = buildFreshnessWarning(provenance)

    if (freshnessWarning != null) {
      logger.warn(freshnessWarning)
    }
  }
}
