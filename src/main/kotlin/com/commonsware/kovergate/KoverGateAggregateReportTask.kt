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

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Builds an aggregate JSON report from a list of per-module [GapReport]s.
 *
 * @param modules per-module gap reports to merge
 * @param prettyPrint whether to format the JSON with indentation
 * @return serialized [AggregateGapReport] JSON string
 */
internal fun buildAggregateJsonOutput(modules: List<GapReport>, prettyPrint: Boolean = false): String {
  val json = Json { this.prettyPrint = prettyPrint }
  val aggregate = AggregateGapReport(passed = modules.all { it.passed }, modules = modules)

  return json.encodeToString<AggregateGapReport>(aggregate)
}

/**
 * Gradle task that aggregates per-submodule gap-report.json files into a single root-level report.
 *
 * Registered by [CoverageGatePlugin] when applied to a project that has subprojects. Reads each submodule's gap report,
 * merges them into an [AggregateGapReport], and writes it to `build/reports/kover/gap-report.json` at the root.
 */
@CacheableTask
public abstract class KoverGateAggregateReportTask : DefaultTask() {
  /** Per-submodule gap report JSON files, collected from each submodule's [KoverGateReportTask] output. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val inputJsonFiles: ConfigurableFileCollection

  /** Whether to pretty-print the aggregate JSON output. Defaults to `false`. */
  @get:Input public abstract val prettyPrintJson: Property<Boolean>

  /** Output path for the aggregate JSON gap report. Defaults to `build/reports/kover/gap-report.json` at root. */
  @get:OutputFile public abstract val jsonReportFile: RegularFileProperty

  /**
   * Gradle project path (e.g., `:`), set by the plugin from [org.gradle.api.Project.getPath]. Used in console output.
   */
  @get:Internal public abstract val projectPath: Property<String>

  /** Reads all available submodule gap reports and writes the merged aggregate to [jsonReportFile]. */
  @TaskAction
  public fun generate() {
    val inputJson = Json.Default
    val modules =
      inputJsonFiles.files
        .filter { it.exists() }
        .sortedBy { it.path }
        .map { inputJson.decodeFromString<GapReport>(it.readText()) }

    val outFile = jsonReportFile.get().asFile

    outFile.parentFile.mkdirs()
    outFile.writeText(buildAggregateJsonOutput(modules, prettyPrintJson.get()))

    val status = if (modules.all { it.passed }) "PASSED" else "FAILED"
    val moduleWord = if (modules.size == 1) "module" else "modules"

    logger.lifecycle("Aggregate coverage gate $status for ${projectPath.get()} (${modules.size} $moduleWord)")
  }
}
