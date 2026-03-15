/**
 * Serialization-only DTOs for gap-report.json.
 *
 * These are separate from the computation types (MetricResult, FileCoverage) to keep serialization concerns out of the
 * domain model. FileCoverage carries additional fields (missedBranches, totalBranches) used by buildConsoleOutput that
 * are intentionally not written to JSON.
 */
@file:Suppress("unused")

package com.commonsware.kovergate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level structure written to gap-report.json, describing whether a module passed its coverage thresholds and which
 * lines were missed.
 *
 * @param module the Gradle project path (e.g., `:app`) of the module being reported
 * @param passed true if every metric met or exceeded its threshold; false otherwise
 * @param metrics map from metric name (e.g., `"LINE"`, `"BRANCH"`) to its coverage details
 * @param files list of source files that have uncovered lines or missed branch lines
 */
@Serializable
data class GapReport(
  val module: String,
  val passed: Boolean,
  val metrics: Map<String, MetricJson>,
  val files: List<FileGap>,
)

/**
 * Coverage details for a single metric (e.g., line or branch coverage) within a module.
 *
 * @param covered the number of covered units (lines or branches)
 * @param total the total number of instrumented units
 * @param percent the coverage percentage, computed as `covered / total * 100`
 * @param threshold the minimum required coverage percentage for this metric
 * @param passed true if [percent] is greater than or equal to [threshold]
 */
@Serializable
data class MetricJson(val covered: Int, val total: Int, val percent: Double, val threshold: Int, val passed: Boolean)

/**
 * Identifies uncovered and missed-branch lines within a single source file.
 *
 * @param name the simple file name (e.g., "Foo.kt")
 * @param packageName the package of the file (e.g., "com.example.app"), serialized as the JSON key `"package"`
 * @param uncoveredLines line numbers within the file that have no coverage
 * @param missedBranchLines line numbers that contain branches with at least one missed branch
 */
@Serializable
data class FileGap(
  val name: String,
  @SerialName("package") val packageName: String,
  val uncoveredLines: List<Int>,
  val missedBranchLines: List<Int>,
)
