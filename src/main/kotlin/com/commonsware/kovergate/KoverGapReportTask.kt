package com.commonsware.kovergate

import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

// ─── Pure data + functions (unit-testable without Gradle) ──────────────────

private const val PERCENT_SCALE = 100.0
private const val ONE_DECIMAL_SCALE = 10.0

/** Result of computing coverage for a single metric. */
data class MetricResult(
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
 * Builds a JSON-formatted gap report from coverage results. Converts metrics to MetricJson DTOs and includes only files
 * with gaps.
 */
internal fun buildJsonOutput(
  /** The Gradle project path (e.g., `:app`). */
  projectPath: String,
  /** True if the coverage gate passed overall. */
  passed: Boolean,
  /** Computed results for each coverage metric. */
  metricResults: Map<CoverageMetric, MetricResult>,
  /** Source files that have uncovered lines or missed branch lines. */
  filesWithGaps: List<FileCoverage>,
  /** Whether to format the JSON output with indentation. Defaults to `false`. */
  prettyPrint: Boolean = false,
): String {
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
    )

  return json.encodeToString<GapReport>(report)
}

// ─── The Gradle task ────────────────────────────────────────────────────────

/**
 * Gradle task that validates Kotlin/Kotlin-JVM coverage against configured thresholds and reports gaps in both
 * human-readable and JSON formats.
 */
@CacheableTask
abstract class KoverGapReportTask : DefaultTask() {
  /** The Kover XML report file input (path-sensitive, cacheable). */
  @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val xmlReportFile: RegularFileProperty

  /** Minimum required line coverage percentage. Defaults to `100`. */
  @get:Input abstract val minLineCoverage: Property<Int>

  /** Minimum required branch coverage percentage. Defaults to `100`. */
  @get:Input abstract val minBranchCoverage: Property<Int>

  /** Minimum required instruction coverage percentage. Defaults to `100`. */
  @get:Input abstract val minInstructionCoverage: Property<Int>

  /** Minimum required method coverage percentage. Defaults to `100`. */
  @get:Input abstract val minMethodCoverage: Property<Int>

  /** Minimum required class coverage percentage. Defaults to `100`. */
  @get:Input abstract val minClassCoverage: Property<Int>

  /** Metrics to exclude from coverage validation. Defaults to empty set. */
  @get:Input abstract val disabledMetrics: SetProperty<CoverageMetric>

  /**
   * Whether to pretty-print the JSON gap report output.
   *
   * When `true`, the JSON is formatted with indentation for human readability. When `false` (the default), compact JSON
   * is emitted.
   */
  @get:Input abstract val prettyPrintJson: Property<Boolean>

  @get:OutputFile abstract val jsonReportFile: RegularFileProperty

  @get:Internal abstract val projectPath: Property<String>

  @TaskAction
  fun generate() {
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

    // Write JSON (always, even on pass)
    val jsonFile = jsonReportFile.get().asFile

    jsonFile.parentFile.mkdirs()
    jsonFile.writeText(
      buildJsonOutput(projectPath.get(), overallPassed, metricResults, filesWithGaps, prettyPrintJson.get())
    )

    // Print console summary
    val summary = buildConsoleOutput(projectPath.get(), metricResults, filesWithGaps, overallPassed)

    logger.lifecycle(summary)

    if (!overallPassed) {
      throw GradleException(summary)
    }
  }
}
