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

/** Regex matching a unified-diff hunk header, capturing the new-side start line and optional count. */
private val HUNK_HEADER = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")

/** Regex matching the new-file path line (`+++ b/<path>`). */
private val NEW_FILE_HEADER = Regex("""^\+\+\+ (?:b/)?(.+)$""")

/**
 * Parses `git diff --unified=0` output into a map from new-side file path to the set of line numbers that were added or
 * changed on the new (branch) side.
 *
 * Lines deleted-only (a hunk whose new-side count is zero) contribute nothing. Paths are returned exactly as git emits
 * them on the `+++ b/` line, i.e. relative to the git repository root.
 *
 * @param diffText the raw output of `git diff --unified=0 <base>...HEAD`
 * @return map from git-root-relative path to the set of changed line numbers on the new side
 */
internal fun parseUnifiedDiff(diffText: String): Map<String, Set<Int>> {
  val result = mutableMapOf<String, MutableSet<Int>>()
  var current: MutableSet<Int>? = null

  for (line in diffText.lineSequence()) {
    val fileMatch = NEW_FILE_HEADER.matchEntire(line)

    if (fileMatch != null) {
      val path = fileMatch.groupValues[1]

      current = if (path == "/dev/null") null else result.getOrPut(path) { mutableSetOf() }
      continue
    }

    val hunkMatch = HUNK_HEADER.find(line)

    if (hunkMatch != null && current != null) {
      val start = hunkMatch.groupValues[1].toInt()
      val count = hunkMatch.groupValues[2].toIntOrNull() ?: 1

      for (offset in 0 until count) current.add(start + offset)
    }
  }

  return result.filterValues { it.isNotEmpty() }
}
