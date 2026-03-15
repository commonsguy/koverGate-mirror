package com.commonsware.kovergate

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*

// ─── Pure data + functions (unit-testable without Gradle) ──────────────────

data class MetricResult(val covered: Int, val total: Int, val percent: Double, val threshold: Int, val passed: Boolean)

internal fun computePercent(covered: Int, missed: Int): Double {
  if (covered == 0 && missed == 0) return 100.0
  return BigDecimal.valueOf(covered.toLong())
    .multiply(BigDecimal.valueOf(100))
    .divide(BigDecimal.valueOf((covered + missed).toLong()), 1, RoundingMode.HALF_UP)
    .toDouble()
}

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

internal fun buildConsoleOutput(
  projectPath: String,
  metricResults: Map<CoverageMetric, MetricResult>,
  filesWithGaps: List<FileCoverage>,
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

internal fun buildJsonOutput(
  projectPath: String,
  passed: Boolean,
  metricResults: Map<CoverageMetric, MetricResult>,
  filesWithGaps: List<FileCoverage>,
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

@CacheableTask
abstract class KoverGapReportTask : DefaultTask() {

  @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val xmlReportFile: RegularFileProperty

  @get:Input abstract val minLineCoverage: Property<Int>

  @get:Input abstract val minBranchCoverage: Property<Int>

  @get:Input abstract val minInstructionCoverage: Property<Int>

  @get:Input abstract val minMethodCoverage: Property<Int>

  @get:Input abstract val minClassCoverage: Property<Int>

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
