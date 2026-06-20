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

/** Selects which subset of coverage gaps the scoped report retains. */
internal enum class ScopeType {
  /** Keep only gap lines that were added or changed on the current branch relative to a base ref. */
  BRANCH
}

/** Filters a full [GapReport] down to a scope-specific subset of gaps. */
internal interface ScopeFilter {
  /**
   * Applies the scope to [report], returning a new [GapReport] containing only the in-scope files and gap lines.
   *
   * @param report the full, unfiltered gap report
   * @return a scoped [GapReport] with `metrics` empty and `passed` reflecting whether any in-scope gaps remain
   */
  fun apply(report: GapReport): GapReport
}

/**
 * A [ScopeFilter] that retains only the uncovered and missed-branch lines that fall within the lines changed on the
 * current branch.
 *
 * @param changedLinesByPath map from git-root-relative path to the set of lines changed on the branch
 * @param resolvePath resolves a [FileGap] to its git-root-relative path, or `null` if the file cannot be located
 */
internal class BranchDiffScopeFilter(
  private val changedLinesByPath: Map<String, Set<Int>>,
  private val resolvePath: (FileGap) -> String?,
) : ScopeFilter {
  override fun apply(report: GapReport): GapReport {
    val scopedFiles =
      report.files.mapNotNull { file ->
        val path = resolvePath(file) ?: return@mapNotNull null
        val changed = changedLinesByPath[path] ?: emptySet()
        val uncovered = file.uncoveredLines.filter { it in changed }
        val missedBranch = file.missedBranchLines.filter { it.line in changed }

        if (uncovered.isEmpty() && missedBranch.isEmpty()) {
          null
        } else {
          FileGap(file.name, file.packageName, uncovered, missedBranch)
        }
      }

    return GapReport(module = report.module, passed = scopedFiles.isEmpty(), metrics = emptyMap(), files = scopedFiles)
  }
}
