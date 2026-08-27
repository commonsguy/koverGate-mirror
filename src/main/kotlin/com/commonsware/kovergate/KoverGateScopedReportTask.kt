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

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

/**
 * Gradle task that reads a full gap report and emits a scoped version filtered to a selectable scope (e.g., lines
 * changed on the current branch).
 *
 * The task does not fail the build on in-scope gaps — it is a reporting task, not a gate. Output is written to
 * `build/reports/kover/scoped-gap-report.json` with the same `GapReport` shape, but `metrics` is empty and `passed` is
 * `true` only when no in-scope gaps remain.
 */
@DisableCachingByDefault(because = "Git working-tree state is an untracked input; caching would produce stale results")
public abstract class KoverGateScopedReportTask : DefaultTask() {
  /** The input gap-report JSON file produced by `koverGateReport`. */
  @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) public abstract val gapReportFile: RegularFileProperty

  /** The module's source-root directories (e.g., `src/main/kotlin`, `src/main/java`). */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val sourceRoots: ConfigurableFileCollection

  /**
   * The base git reference to diff against (e.g., `main`).
   *
   * Defaults to the Gradle property `koverGateBase` if set, otherwise `main`. Can be overridden with the `--base` task
   * option for single-module invocations.
   */
  @get:Input public abstract val baseRef: Property<String>

  /**
   * The scope filter to apply as a string (e.g., `"branch"`). Corresponds to a [ScopeType] name, case-insensitive.
   *
   * Stored as a `String` rather than `Property<ScopeType>` so the abstract property does not carry an `internal` type
   * in its signature, which would cause Gradle's bytecode generator to fail. Defaults to `"branch"`. Can be overridden
   * with the `--scope` task option.
   */
  @get:Input public abstract val scope: Property<String>

  /**
   * The git diff result: git root and changed lines per file.
   *
   * Set by the plugin from a `ValueSource` to ensure configuration-cache safety. Marked `@Internal` because the
   * `ValueSource` itself defines cache behavior, not the task property.
   */
  @get:Internal public abstract val gitDiff: Property<GitDiffResult>

  /**
   * The output scoped-gap-report JSON file.
   *
   * Defaults to `build/reports/kover/scoped-gap-report.json`.
   */
  @get:OutputFile public abstract val scopedReportFile: RegularFileProperty

  /**
   * Whether to pretty-print the JSON gap report output.
   *
   * When `true`, the JSON is formatted with indentation for human readability. When `false` (the default), compact JSON
   * is emitted.
   */
  @get:Input public abstract val prettyPrintJson: Property<Boolean>

  /**
   * Gradle project path (e.g., `:app`), set by the plugin from [org.gradle.api.Project.getPath]. Used as the module
   * identifier in JSON output.
   */
  @get:Internal public abstract val projectPath: Property<String>

  /**
   * Sets the base git reference (supports task option `--base`).
   *
   * @param value the base ref to set
   */
  @Option(option = "base", description = "Base git ref to diff against (default: main)")
  internal fun setBase(value: String) {
    baseRef.set(value)
  }

  /**
   * Sets the scope type (supports task option `--scope`).
   *
   * @param value the scope name to set (e.g., `"branch"`)
   */
  @Option(option = "scope", description = "Scope filter to apply (default: branch)")
  internal fun setScope(value: String) {
    scope.set(value)
  }

  @TaskAction
  internal fun generate() {
    val gapReportJsonFile = gapReportFile.get().asFile
    val fullReport = Json.decodeFromString<GapReport>(gapReportJsonFile.readText())

    // Build path resolver from source roots and git root
    val gitRoot = gitDiff.get().gitRoot
    val sourceRootsList = sourceRoots.files.toList()
    val resolvePath = { gap: FileGap -> resolveGitRelativePath(gap, sourceRootsList, gitRoot) }

    // Select and apply the scope filter
    val untrackedPaths = gitDiff.get().untrackedPaths
    val filter =
      when (ScopeType.valueOf(scope.get().uppercase())) {
        ScopeType.BRANCH ->
          BranchDiffScopeFilter(
            changedLinesByPath = gitDiff.get().changedLinesByPath,
            untrackedPaths = untrackedPaths,
            resolvePath = resolvePath,
          )
      }

    val scoped = filter.apply(fullReport)

    // Write scoped report JSON
    val scopedFile = scopedReportFile.get().asFile

    scopedFile.parentFile.mkdirs()
    val json = Json { prettyPrint = prettyPrintJson.get() }
    scopedFile.writeText(json.encodeToString<GapReport>(scoped))

    // Log a concise console summary (no throw on failure for a reporting task)
    val status = if (scoped.passed) "PASSED" else "FAILED"
    val gapCount = scoped.files.size
    val fileSuffix = if (gapCount == 1) "file" else "files"
    val untrackedCount = scoped.files.count { resolvePath(it) in untrackedPaths }
    val untrackedNote = if (untrackedCount > 0) ", $untrackedCount untracked" else ""
    val summary =
      "Scoped coverage gate [$status] for ${projectPath.get()} ($gapCount $fileSuffix with in-scope gaps$untrackedNote)"

    logger.lifecycle(summary)
  }
}
