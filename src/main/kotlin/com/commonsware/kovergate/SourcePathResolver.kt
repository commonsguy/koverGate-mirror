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

/**
 * Resolves a [FileGap] to a path relative to the git repository root, matching the path form emitted by `git diff`.
 *
 * Combines each source root with the gap's slash-separated package and simple file name, returning the first candidate
 * that exists on disk. Returns `null` when the file is not found under any source root.
 *
 * @param fileGap the coverage gap whose source file should be located
 * @param sourceRoots the module's source-root directories, in priority order
 * @param gitRoot the git repository root, used to relativize the resolved path
 * @return the git-root-relative path with `/` separators, or `null` if not found
 */
internal fun resolveGitRelativePath(fileGap: FileGap, sourceRoots: List<File>, gitRoot: File): String? {
  val relativeWithinRoot = "${fileGap.packageName}/${fileGap.name}"

  for (root in sourceRoots) {
    val candidate = File(root, relativeWithinRoot)

    if (candidate.exists()) {
      return gitRoot.toPath().relativize(candidate.toPath()).joinToString("/")
    }
  }

  return null
}
