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

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/**
 * Parsed result of inspecting branch changes: the git root and the changed lines per file.
 *
 * @param gitRoot the root directory of the git repository
 * @param changedLinesByPath map from git-root-relative path to the set of lines changed on the branch
 */
public data class GitDiffResult(val gitRoot: File, val changedLinesByPath: Map<String, Set<Int>>)

/**
 * A configuration-cache-safe [ValueSource] that reads the lines changed on the current branch relative to a base ref.
 *
 * Runs `git rev-parse --show-toplevel`, `git merge-base <baseRef> HEAD`, and `git diff --unified=0 <mergeBase>` in the
 * project directory (the last diffs the working tree, so uncommitted changes are included), then delegates to
 * [parseUnifiedDiff].
 */
internal abstract class GitDiffValueSource @Inject constructor(private val execOperations: ExecOperations) :
  ValueSource<GitDiffResult, GitDiffValueSource.Params> {

  /** Parameters for [GitDiffValueSource]. */
  internal interface Params : ValueSourceParameters {
    /** The directory in which to run git (the Gradle project directory). */
    val projectDirectory: DirectoryProperty

    /** The base ref to diff against (e.g. `main`). */
    val baseRef: Property<String>
  }

  override fun obtain(): GitDiffResult {
    val projectDir = parameters.projectDirectory.get().asFile
    val base = parameters.baseRef.get()
    val gitRoot = File(runGit(projectDir, listOf("rev-parse", "--show-toplevel")).trim())
    val mergeBase = runGit(projectDir, listOf("merge-base", base, "HEAD")).trim()
    // Diff the working tree (no HEAD) so uncommitted changes are included and line numbers match the compiled source.
    val diff = runGit(projectDir, listOf("diff", "--unified=0", mergeBase))

    return GitDiffResult(gitRoot, parseUnifiedDiff(diff))
  }

  private fun runGit(workingDir: File, args: List<String>): String {
    val stdout = ByteArrayOutputStream()
    val result = execOperations.exec {
      isIgnoreExitValue = true
      workingDir(workingDir)
      commandLine(listOf("git") + args)
      standardOutput = stdout
    }

    if (result.exitValue != 0) {
      throw GradleException(
        "KoverGate: git command failed: git ${args.joinToString(" ")} (exit ${result.exitValue}). " +
          "Ensure this is a git repository and the base ref exists."
      )
    }

    return stdout.toString(Charsets.UTF_8)
  }
}
