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
 * Parsed result of inspecting branch changes: the git root, the changed lines per file, and untracked file paths.
 *
 * @param gitRoot the root directory of the git repository
 * @param changedLinesByPath map from git-root-relative path to the set of lines changed on the branch
 * @param untrackedPaths git-root-relative paths of files that are untracked (never `git add`-ed) and not gitignored
 */
public data class GitDiffResult(
  val gitRoot: File,
  val changedLinesByPath: Map<String, Set<Int>>,
  val untrackedPaths: Set<String>,
)

/**
 * Builds the arguments for the `git diff` that finds the lines changed since [mergeBase].
 *
 * @param mergeBase the merge-base commit to diff the working tree against
 * @param ignoreSpaceChange whether to add `--ignore-space-change`, so lines whose only change is the amount of
 *   whitespace (such as re-indentation) are left out of the diff
 * @return the argument list to pass to `git`
 */
internal fun buildDiffArgs(mergeBase: String, ignoreSpaceChange: Boolean): List<String> =
  listOf("diff", "--unified=0") + listOfNotNull("--ignore-space-change".takeIf { ignoreSpaceChange }) + mergeBase

/**
 * A configuration-cache-safe [ValueSource] that reads the lines changed on the current branch relative to a base ref,
 * plus the repository's untracked, non-gitignored file paths.
 *
 * Runs `git rev-parse --show-toplevel`, `git merge-base <baseRef> HEAD`, `git diff --unified=0 <mergeBase>` (diffs the
 * working tree, so uncommitted changes are included; with `--ignore-space-change` when requested), and `git ls-files
 * --others --exclude-standard --full-name` in the project directory, then delegates to [parseUnifiedDiff] for the diff
 * output.
 */
internal abstract class GitDiffValueSource @Inject constructor(private val execOperations: ExecOperations) :
  ValueSource<GitDiffResult, GitDiffValueSource.Params> {

  /** Parameters for [GitDiffValueSource]. */
  internal interface Params : ValueSourceParameters {
    /** The directory in which to run git (the Gradle project directory). */
    val projectDirectory: DirectoryProperty

    /** The base ref to diff against (e.g. `main`). */
    val baseRef: Property<String>

    /** Whether to diff with `--ignore-space-change`, leaving out lines whose only change is whitespace. */
    val ignoreSpaceChange: Property<Boolean>
  }

  override fun obtain(): GitDiffResult {
    val projectDir = parameters.projectDirectory.get().asFile
    val base = parameters.baseRef.get()
    val gitRoot = File(runGit(projectDir, listOf("rev-parse", "--show-toplevel")).trim())
    val mergeBase = runGit(projectDir, listOf("merge-base", base, "HEAD")).trim()
    // Diff the working tree (no HEAD) so uncommitted changes are included and line numbers match the compiled source.
    val diff = runGit(projectDir, buildDiffArgs(mergeBase, parameters.ignoreSpaceChange.get()))
    // --full-name forces repo-root-relative output so paths match parseUnifiedDiff's form even when run from a
    // submodule.
    val untrackedOutput = runGit(projectDir, listOf("ls-files", "--others", "--exclude-standard", "--full-name"))
    val untrackedPaths = untrackedOutput.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    return GitDiffResult(gitRoot, parseUnifiedDiff(diff), untrackedPaths)
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
