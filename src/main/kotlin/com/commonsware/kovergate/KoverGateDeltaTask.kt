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

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Renders the console summary for a computed [GapDeltaReport]: a line noting an absent baseline, a line noting no
 * change, or one line per moved metric plus a changed-file count.
 *
 * @param projectPath the Gradle project path (e.g., `:app`) the delta was computed for
 * @param delta the computed delta to summarize
 * @return the summary text to print
 */
internal fun renderDeltaSummary(projectPath: String, delta: GapDeltaReport): String =
  when {
    delta.baseline == BaselineStatus.ABSENT ->
      "Coverage delta for $projectPath: no baseline available (first build, or a build after clean)"
    delta.metrics.isEmpty() && delta.files.isEmpty() ->
      "Coverage delta for $projectPath: no change since the previous build"
    else -> renderMovedSummary(projectPath, delta)
  }

/**
 * Renders the headroom-movement parenthetical for a delta's per-metric summary line: `(headroom B → C)` showing the
 * baseline and current values, `(headroom C, unchanged)` when [MetricDelta.headroomDelta] is zero, or an empty string
 * when the metric has no headroom on either side (zero total on the current side, or on the baseline side).
 */
private fun renderHeadroomMovement(metric: MetricDelta): String {
  val headroom = metric.headroom
  val headroomDelta = metric.headroomDelta

  return when {
    headroom == null || headroomDelta == null -> ""
    headroomDelta == 0 -> "  (headroom $headroom, unchanged)"
    else -> "  (headroom ${headroom - headroomDelta} → $headroom)"
  }
}

/** Renders the multi-line summary for a delta with a present baseline and at least one metric or file movement. */
private fun renderMovedSummary(projectPath: String, delta: GapDeltaReport): String {
  val fileCount = delta.files.size
  val fileSuffix = if (fileCount == 1) "file" else "files"

  return buildString {
    appendLine("Coverage delta for $projectPath since the previous build:")

    for ((name, metric) in delta.metrics.entries.sortedBy { it.key }) {
      val sign = if (metric.percentDelta >= 0) "+" else ""
      val headroomSuffix = renderHeadroomMovement(metric)

      appendLine("  $name: $sign${metric.percentDelta}%$headroomSuffix")
    }

    append("  $fileCount $fileSuffix changed")
  }
}

/**
 * Diagnostic Gradle task that computes how coverage moved since the previous build. Reads the current build's
 * [gapReportFile] and, when present, the previous build's [baselineReportFile] (rotated aside by
 * `koverGateGenerateReport`), and writes their [GapDeltaReport] to [deltaReportFile]. Never fails the build: a missing
 * or malformed baseline is reported as [BaselineStatus.ABSENT], not as an error.
 */
@DisableCachingByDefault(because = "Compares against previous-build state, which is not a declared input")
public abstract class KoverGateDeltaTask : DefaultTask() {
  /** The input gap-report JSON file produced by `koverGateGenerateReport` for this build. */
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) public abstract val gapReportFile: RegularFileProperty

  /**
   * The previous build's gap report, rotated aside by `koverGateGenerateReport`. May not exist on disk on the first
   * build or after `clean`, so it is declared `@Internal` rather than `@InputFile` (see §1 finding 4 of the sketch);
   * read defensively.
   */
  @get:Internal public abstract val baselineReportFile: RegularFileProperty

  /** The output delta JSON file. Defaults to `build/reports/kover/gap-delta.json`. */
  @get:OutputFile public abstract val deltaReportFile: RegularFileProperty

  /**
   * Whether to pretty-print the JSON delta report output.
   *
   * When `true`, the JSON is formatted with indentation for human readability. When `false` (the default), compact JSON
   * is emitted.
   */
  @get:Input public abstract val prettyPrintJson: Property<Boolean>

  /**
   * Gradle project path (e.g., `:app`), set by the plugin from [org.gradle.api.Project.getPath]. Used as the module
   * identifier in JSON output and the console summary.
   */
  @get:Internal public abstract val projectPath: Property<String>

  /**
   * Reads [file] as a [GapReport], or returns `null` when the file does not exist or is malformed.
   *
   * A missing file means no prior build produced a baseline (first build, or a build immediately after `clean`), which
   * is the expected steady state and not logged. A malformed file is a leftover from a prior failure mode and must not
   * fail this build; it is logged at `warn` and treated the same as a missing baseline.
   *
   * @param file the baseline gap-report file to read
   * @return the parsed [GapReport], or `null` if [file] does not exist or could not be parsed
   */
  private fun readBaseline(file: File): GapReport? {
    if (!file.exists()) return null

    return try {
      Json.decodeFromString<GapReport>(file.readText())
    } catch (e: SerializationException) {
      logger.warn("KoverGate: baseline gap report at $file is malformed, treating as absent: ${e.message}")
      null
    }
  }

  /** Reads the current and baseline gap reports, computes their delta, writes it, and prints a summary. */
  @TaskAction
  internal fun generate() {
    val current = Json.decodeFromString<GapReport>(gapReportFile.get().asFile.readText())
    val baseline = readBaseline(baselineReportFile.get().asFile)
    val delta = computeGapDelta(baseline, current)

    val deltaFile = deltaReportFile.get().asFile

    deltaFile.parentFile.mkdirs()
    val json = Json { prettyPrint = prettyPrintJson.get() }
    deltaFile.writeText(json.encodeToString(delta))

    logger.lifecycle(renderDeltaSummary(projectPath.get(), delta))
  }
}
