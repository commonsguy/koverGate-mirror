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

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

// ─── Pure output builder (unit-testable without Gradle) ─────────────────────

/**
 * How much of the report-contention guard (see `ReportContention.kt`) applies to a module, determined by whether a
 * producer task for the Kover XML is known (see the sketch's §6).
 *
 * - [FULL]: a producer task is known; both before-read and after-read detection are active.
 * - [PARTIAL]: no producer task is known, so no stamp is written; only after-read detection is active.
 */
internal enum class ContentionGuardReach {
  FULL,
  PARTIAL,
}

/**
 * Parameters for building the doctor output.
 *
 * @param projectPath the Gradle project path (e.g., `:app`)
 * @param thresholds the configured minimum coverage percentage per metric
 * @param disabledMetrics metrics excluded from gating
 * @param xmlPath absolute path of the Kover XML the gate will read
 * @param xmlExists whether that XML currently exists on disk
 * @param provenance freshness provenance for the XML, or null when the XML does not yet exist
 * @param resolvedExcludes fully-qualified package names excluded via `attributeCoverageFrom`
 * @param guardReach how much of the report-contention guard applies to this module, defaults to
 *   [ContentionGuardReach.FULL]
 */
internal data class DoctorOutputParams(
  val projectPath: String,
  val thresholds: Map<CoverageMetric, Int>,
  val disabledMetrics: Set<CoverageMetric>,
  val xmlPath: String,
  val xmlExists: Boolean,
  val provenance: SourceProvenance?,
  val resolvedExcludes: List<String>,
  val guardReach: ContentionGuardReach = ContentionGuardReach.FULL,
)

/**
 * Builds the human-readable diagnostic that `koverGateDoctor` prints: the per-metric thresholds and their
 * enabled/disabled state, the Kover XML the gate will read and whether it exists, the XML's freshness (from provenance,
 * when available), and the packages excluded via `attributeCoverageFrom`.
 *
 * @param params the parameters for building doctor output
 * @return the formatted multi-line diagnostic text
 */
internal fun buildDoctorOutput(params: DoctorOutputParams): String = buildString {
  val projectPath = params.projectPath
  val thresholds = params.thresholds
  val disabledMetrics = params.disabledMetrics
  val xmlPath = params.xmlPath
  val xmlExists = params.xmlExists
  val provenance = params.provenance
  val resolvedExcludes = params.resolvedExcludes
  val guardReach = params.guardReach
  appendLine("KoverGate doctor for $projectPath")
  appendLine()

  appendLine("Thresholds:")

  for (metric in CoverageMetric.entries) {
    val state = if (metric in disabledMetrics) "disabled" else "enabled"
    val threshold = thresholds[metric]
    val thresholdText = if (threshold != null) "$threshold%" else "n/a"

    appendLine("  ${metric.name}: $thresholdText ($state)")
  }

  appendLine()

  appendLine("Kover XML: $xmlPath")
  appendLine("  exists: $xmlExists")

  if (provenance != null) {
    appendLine("  Freshness (build-script timestamp heuristic):")
    appendLine("    modified: ${provenance.xmlModified}")
    appendLine("    newest build script: ${provenance.newestBuildScriptModified ?: "n/a"}")
    appendLine("    stale: ${provenance.stale}")
  } else {
    appendLine("  (run koverGateGenerateReport to produce it)")
  }

  if (guardReach == ContentionGuardReach.PARTIAL) {
    appendLine(
      "  Report-contention guard: PARTIAL (no producer task known; set xmlReportTask to restore full detection)"
    )
  } else {
    appendLine("  Report-contention guard: FULL")
  }

  appendLine()

  appendLine("Excluded packages (attributeCoverageFrom):")

  if (resolvedExcludes.isEmpty()) {
    append("  (none)")
  } else {
    append(resolvedExcludes.joinToString("\n") { "  $it" })
  }
}

// ─── The Gradle task ────────────────────────────────────────────────────────

/**
 * Diagnostic Gradle task that prints, in one place, the coverage thresholds, the Kover XML the gate will read, that
 * XML's freshness, and the packages excluded via `attributeCoverageFrom`. It never fails the build and never runs
 * tests; it reports whatever state is currently on disk so a stale or missing report can be spotted immediately.
 */
@DisableCachingByDefault(because = "Diagnostic task that always reports current on-disk state")
public abstract class KoverGateDoctorTask : DefaultTask() {
  /** Gradle project path (e.g., `:app`), set by the plugin from [org.gradle.api.Project.getPath]. */
  @get:Internal public abstract val projectPath: Property<String>

  /** The Kover XML the gate will read. Not declared as an input: the task reports its state, present or absent. */
  @get:Internal public abstract val xmlReportFile: RegularFileProperty

  /** Build scripts whose modification times are compared against the XML to report freshness. */
  @get:Internal public abstract val buildScripts: ConfigurableFileCollection

  /** Minimum required line coverage percentage. */
  @get:Internal public abstract val minLineCoverage: Property<Int>

  /** Minimum required branch coverage percentage. */
  @get:Internal public abstract val minBranchCoverage: Property<Int>

  /** Minimum required instruction coverage percentage. */
  @get:Internal public abstract val minInstructionCoverage: Property<Int>

  /** Minimum required method coverage percentage. */
  @get:Internal public abstract val minMethodCoverage: Property<Int>

  /** Minimum required class coverage percentage. */
  @get:Internal public abstract val minClassCoverage: Property<Int>

  /** Metrics excluded from gating. */
  @get:Internal public abstract val disabledMetrics: SetProperty<CoverageMetric>

  /** Fully-qualified package names excluded via `attributeCoverageFrom`. */
  @get:Internal public abstract val resolvedExcludes: ListProperty<String>

  /**
   * How much of the report-contention guard applies to this module, from the plugin's producer-task resolution (see the
   * sketch's §6). Defaults to [ContentionGuardReach.FULL] when unset.
   */
  @get:Internal internal abstract val guardReach: Property<ContentionGuardReach>

  /** Prints the diagnostic. Never fails the build. */
  @TaskAction
  internal fun diagnose() {
    val xmlFile = xmlReportFile.get().asFile
    val xmlExists = xmlFile.exists()
    val provenance =
      if (xmlExists) {
        val buildScriptTimes = buildScripts.files.filter { it.exists() }.map { it.lastModified() }

        computeProvenance(xmlFile.absolutePath, xmlFile.lastModified(), buildScriptTimes)
      } else {
        null
      }
    val thresholds =
      mapOf(
        CoverageMetric.LINE to minLineCoverage.get(),
        CoverageMetric.BRANCH to minBranchCoverage.get(),
        CoverageMetric.INSTRUCTION to minInstructionCoverage.get(),
        CoverageMetric.METHOD to minMethodCoverage.get(),
        CoverageMetric.CLASS to minClassCoverage.get(),
      )
    val output =
      buildDoctorOutput(
        DoctorOutputParams(
          projectPath = projectPath.get(),
          thresholds = thresholds,
          disabledMetrics = disabledMetrics.get(),
          xmlPath = xmlFile.absolutePath,
          xmlExists = xmlExists,
          provenance = provenance,
          resolvedExcludes = resolvedExcludes.get(),
          guardReach = guardReach.getOrElse(ContentionGuardReach.FULL),
        )
      )

    logger.lifecycle(output)
  }
}
