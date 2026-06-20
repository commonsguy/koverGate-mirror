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
 * @param source provenance describing the XML the report was computed from and its freshness, or null if unset
 */
@Serializable
public data class GapReport(
  val module: String,
  val passed: Boolean,
  val metrics: Map<String, MetricJson>,
  val files: List<FileGap>,
  val source: SourceProvenance? = null,
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
public data class MetricJson(
  val covered: Int,
  val total: Int,
  val percent: Double,
  val threshold: Int,
  val passed: Boolean,
)

/**
 * Identifies uncovered and missed-branch lines within a single source file.
 *
 * @param name the simple file name (e.g., "Foo.kt")
 * @param packageName the package of the file (e.g., "com.example.app"), serialized as the JSON key `"package"`
 * @param uncoveredLines line numbers within the file that have no coverage
 * @param missedBranchLines per-line branch detail (missed of total) for lines with at least one missed branch
 */
@Serializable
public data class FileGap(
  val name: String,
  @SerialName("package") val packageName: String,
  val uncoveredLines: List<Int>,
  val missedBranchLines: List<BranchGap>,
)

/**
 * Per-line branch coverage detail within a [FileGap]: how many of a source line's branches were missed out of its
 * total.
 *
 * @param line the 1-based source line number containing the branches
 * @param missed the number of branches on this line with no coverage
 * @param total the total number of branches on this line (missed plus covered)
 */
@Serializable public data class BranchGap(val line: Int, val missed: Int, val total: Int)

/**
 * Top-level structure for the root-level aggregate gap-report.json in multi-module projects.
 *
 * @param passed true if every module passed its coverage thresholds; false if any failed
 * @param modules list of per-module gap reports, one entry per submodule that ran koverGateReport
 */
@Serializable public data class AggregateGapReport(val passed: Boolean, val modules: List<GapReport>)

/**
 * Provenance for the coverage data a [GapReport] was computed from: which Kover XML file was read, when that file and
 * the module's build scripts were last modified, and whether the XML appears stale relative to those build scripts.
 *
 * All timestamps are ISO-8601 instants in UTC (e.g. `2026-06-18T14:03:11Z`). A `true` [stale] flag means the newest
 * build script is newer than the XML report, so the on-disk coverage may predate the current build configuration; the
 * gap report was still computed faithfully from whatever the XML contained.
 *
 * @param xmlPath absolute path of the Kover XML report the gap report was computed from
 * @param xmlModified ISO-8601 UTC instant the XML report was last modified
 * @param stale true if the newest build script is newer than the XML report
 * @param newestBuildScriptModified ISO-8601 UTC instant of the most recently modified build script, or null if no build
 *   scripts were found
 */
@Serializable
public data class SourceProvenance(
  val xmlPath: String,
  val xmlModified: String,
  val stale: Boolean,
  val newestBuildScriptModified: String? = null,
)
