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
 * Serialization-only DTOs and pure computation for gap-delta.json, describing how coverage moved between the previous
 * build's gap-report.json (the baseline) and the current one.
 */
package com.commonsware.kovergate

import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val ONE_DECIMAL_SCALE = 10.0

/** Whether a baseline gap report was available to compute a delta against. */
@Serializable
public enum class BaselineStatus {
  /** A baseline was found and the delta is computed relative to it. */
  @SerialName("present") PRESENT,

  /** No baseline was available (first build, or a build immediately after `clean`); the delta is empty. */
  @SerialName("absent") ABSENT,
}

/**
 * Which direction a file's coverage gap moved, judged solely by the change in total missed instructions
 * ([FileDelta.missedInstructionsColdDelta] plus [FileDelta.missedInstructionsResidualDelta]) — not by line counts,
 * which can move in the opposite direction from the instruction totals. A file whose instruction sum is unchanged but
 * whose line counts moved is classified [IMPROVED], since only a worsening instruction sum counts as a regression.
 */
@Serializable
public enum class DeltaStatus {
  /** Total missed instructions increased since the baseline. */
  @SerialName("worsened") WORSENED,

  /** Total missed instructions did not increase since the baseline. */
  @SerialName("improved") IMPROVED,
}

/**
 * Top-level structure written to gap-delta.json, describing how coverage moved between the baseline (previous build)
 * and the current build.
 *
 * @param module the Gradle project path (e.g., `:app`) of the module being reported
 * @param baseline whether a baseline gap report was available to compute this delta against
 * @param metrics map from metric name (e.g., `"LINE"`, `"BRANCH"`) to its movement, for metrics present in both reports
 * @param files per-file instruction and line-count movement, for files that changed since the baseline
 */
@Serializable
public data class GapDeltaReport(
  val module: String,
  val baseline: BaselineStatus,
  val metrics: Map<String, MetricDelta> = emptyMap(),
  val files: List<FileDelta> = emptyList(),
)

/**
 * Movement of a single coverage metric between the baseline and the current build.
 *
 * [headroomDelta] and [headroom] are recomputed from each side's `covered`/`total`/`threshold` ([computeHeadroom])
 * rather than read from a stored field, so a baseline written by an older KoverGate — which has no `headroom` field —
 * still yields a correct delta.
 *
 * Two caveats apply to [headroomDelta]: if the threshold changed between the two builds, it conflates that change with
 * the coverage change — headroom is defined relative to the threshold in force, so this is honest but worth knowing
 * when comparing against [coveredDelta]. And a metric that gained instrumented units can lose headroom even while
 * [coveredDelta] is positive, since the required covered count rises with `total`.
 *
 * @param percentDelta the change in coverage percentage (`current - baseline`), rounded to 1 decimal place
 * @param coveredDelta the change in covered units
 * @param totalDelta the change in total instrumented units
 * @param headroomDelta the change in headroom (`current - baseline`), or `null` when either side's headroom is `null`
 *   (zero total on that side)
 * @param headroom the current build's headroom, or `null` when the current side has zero total
 */
@Serializable
public data class MetricDelta(
  val percentDelta: Double,
  val coveredDelta: Int,
  val totalDelta: Int,
  val headroomDelta: Int?,
  val headroom: Int?,
)

/**
 * Movement of a single file's coverage gap between the baseline and the current build. A file present in only one
 * report is treated as though it had all-zero counts in the other, so its deltas equal (or negate) its lone report's
 * values.
 *
 * @param name the simple file name (e.g., "Foo.kt")
 * @param packageName the package of the file (e.g., "com/example"), serialized as the JSON key `"package"`
 * @param missedInstructionsColdDelta change in missed instructions on never-executed lines
 * @param missedInstructionsResidualDelta change in missed instructions on partially-executed lines
 * @param uncoveredLineCountDelta change in the count of uncovered lines
 * @param partiallyCoveredLineCountDelta change in the count of partially covered lines
 * @param missedBranchLineCountDelta change in the count of lines with a missed branch
 * @param status [DeltaStatus.WORSENED] or [DeltaStatus.IMPROVED], judged by total missed-instruction movement
 */
@Serializable
public data class FileDelta(
  val name: String,
  @SerialName("package") val packageName: String,
  val missedInstructionsColdDelta: Int,
  val missedInstructionsResidualDelta: Int,
  val uncoveredLineCountDelta: Int,
  val partiallyCoveredLineCountDelta: Int,
  val missedBranchLineCountDelta: Int,
  val status: DeltaStatus,
)

/** A file's counts relevant to delta computation, normalized so a missing side is treated as all zeros. */
private data class FileCounts(
  val missedInstructionsCold: Int,
  val missedInstructionsResidual: Int,
  val uncoveredLineCount: Int,
  val partiallyCoveredLineCount: Int,
  val missedBranchLineCount: Int,
)

private val ZERO_FILE_COUNTS =
  FileCounts(
    missedInstructionsCold = 0,
    missedInstructionsResidual = 0,
    uncoveredLineCount = 0,
    partiallyCoveredLineCount = 0,
    missedBranchLineCount = 0,
  )

private fun FileGap.toCounts() =
  FileCounts(
    missedInstructionsCold = missedInstructionsCold ?: 0,
    missedInstructionsResidual = missedInstructionsResidual ?: 0,
    uncoveredLineCount = uncoveredLines.size,
    partiallyCoveredLineCount = partiallyCoveredLines.size,
    missedBranchLineCount = missedBranchLines.size,
  )

/**
 * Re-rounds a percentage difference to 1 decimal place, undoing the float noise reintroduced by subtracting two
 * already-rounded percentages.
 */
private fun roundPercentDelta(delta: Double): Double = (delta * ONE_DECIMAL_SCALE).roundToInt() / ONE_DECIMAL_SCALE

/** Computes the delta between two already-rounded coverage percentages, one from each report. */
private fun computeMetricDelta(baseline: MetricJson, current: MetricJson): MetricDelta {
  val baselineHeadroom = computeHeadroom(baseline.covered, baseline.total, baseline.threshold)
  val currentHeadroom = computeHeadroom(current.covered, current.total, current.threshold)
  val headroomDelta =
    if (baselineHeadroom != null && currentHeadroom != null) currentHeadroom - baselineHeadroom else null

  return MetricDelta(
    percentDelta = roundPercentDelta(current.percent - baseline.percent),
    coveredDelta = current.covered - baseline.covered,
    totalDelta = current.total - baseline.total,
    headroomDelta = headroomDelta,
    headroom = currentHeadroom,
  )
}

/** Computes the field-by-field delta between [baseline] and [current] counts, plus the [DeltaStatus] that follows. */
private fun computeFileDelta(name: String, packageName: String, baseline: FileCounts, current: FileCounts): FileDelta {
  val coldDelta = current.missedInstructionsCold - baseline.missedInstructionsCold
  val residualDelta = current.missedInstructionsResidual - baseline.missedInstructionsResidual
  val status = if (coldDelta + residualDelta > 0) DeltaStatus.WORSENED else DeltaStatus.IMPROVED

  return FileDelta(
    name = name,
    packageName = packageName,
    missedInstructionsColdDelta = coldDelta,
    missedInstructionsResidualDelta = residualDelta,
    uncoveredLineCountDelta = current.uncoveredLineCount - baseline.uncoveredLineCount,
    partiallyCoveredLineCountDelta = current.partiallyCoveredLineCount - baseline.partiallyCoveredLineCount,
    missedBranchLineCountDelta = current.missedBranchLineCount - baseline.missedBranchLineCount,
    status = status,
  )
}

/** True when every one of [delta]'s integer counts is zero, meaning the file carries no information to report. */
private fun isUnchanged(delta: FileDelta) =
  delta.missedInstructionsColdDelta == 0 &&
    delta.missedInstructionsResidualDelta == 0 &&
    delta.uncoveredLineCountDelta == 0 &&
    delta.partiallyCoveredLineCountDelta == 0 &&
    delta.missedBranchLineCountDelta == 0

/**
 * Computes how coverage moved between [baseline] (the previous build's gap report) and [current] (this build's gap
 * report).
 *
 * When [baseline] is `null` (no prior build, or a build immediately after `clean`), returns a [GapDeltaReport] with
 * [BaselineStatus.ABSENT] and empty `metrics`/`files` — zeros are never synthesized for a missing baseline, since that
 * would render every pre-existing gap as a brand-new regression.
 *
 * Otherwise, `metrics` carries an entry only for metric names present in both reports (a metric enabled or disabled
 * between builds has no comparable predecessor), and `files` carries one entry per file that changed, matched by
 * `(package, name)`. A file present in only one report is compared against all-zero counts, and a file whose delta is
 * entirely zero is omitted. The result is sorted by total instruction delta (`missedInstructionsColdDelta` plus
 * `missedInstructionsResidualDelta`) descending, then by name ascending as a deterministic tie-break.
 *
 * @param baseline the previous build's gap report, or `null` if none is available
 * @param current this build's gap report
 * @return the computed delta between the two reports
 */
internal fun computeGapDelta(baseline: GapReport?, current: GapReport): GapDeltaReport {
  if (baseline == null) return GapDeltaReport(module = current.module, baseline = BaselineStatus.ABSENT)

  val metrics =
    current.metrics.keys
      .filter { it in baseline.metrics }
      .associateWith { computeMetricDelta(baseline.metrics.getValue(it), current.metrics.getValue(it)) }

  val baselineByKey = baseline.files.associateBy { it.packageName to it.name }
  val currentByKey = current.files.associateBy { it.packageName to it.name }
  val allKeys = baselineByKey.keys + currentByKey.keys
  val files =
    allKeys
      .map { key ->
        val (packageName, name) = key
        val baselineCounts = baselineByKey[key]?.toCounts() ?: ZERO_FILE_COUNTS
        val currentCounts = currentByKey[key]?.toCounts() ?: ZERO_FILE_COUNTS

        computeFileDelta(name, packageName, baselineCounts, currentCounts)
      }
      .filterNot(::isUnchanged)
      .sortedWith(
        compareByDescending<FileDelta> { it.missedInstructionsColdDelta + it.missedInstructionsResidualDelta }
          .thenBy { it.name }
      )

  return GapDeltaReport(module = current.module, baseline = BaselineStatus.PRESENT, metrics = metrics, files = files)
}
