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

private val REUSED_OUTCOMES = setOf(TaskOutcomeLabel.UP_TO_DATE, TaskOutcomeLabel.FROM_CACHE)
private val GATE_RAN_OUTCOMES = setOf(TaskOutcomeLabel.EXECUTED, TaskOutcomeLabel.FAILED)

/**
 * Returns the simple (last-segment) name of a Gradle task path.
 *
 * @param taskPath a fully-qualified Gradle task path (e.g. `":a:b:koverGateReport"`)
 * @return the segment after the final colon (e.g. `"koverGateReport"`)
 */
internal fun koverTaskSimpleName(taskPath: String): String = taskPath.substringAfterLast(':')

/**
 * Returns the project path that owns a Gradle task path.
 *
 * @param taskPath a fully-qualified Gradle task path (e.g. `":a:koverGateReport"`)
 * @return the owning project path (e.g. `":a"`), with the root project represented as `":"`
 */
internal fun koverProjectOf(taskPath: String): String {
  val idx = taskPath.lastIndexOf(':')
  if (idx <= 0) return ":"

  return taskPath.substring(0, idx)
}

/**
 * Builds producer-reuse freshness warnings from recorded task outcomes. A warning is produced for a project when its
 * `koverGateReport` gate actually ran (executed or failed) this build while that project's Kover XML producer task
 * (`koverXmlReport*`) was served up-to-date or from cache — the situation in which a developer is reading a verdict
 * computed from a Kover XML that Gradle did not regenerate.
 *
 * @param outcomes map of fully-qualified task path to its recorded outcome label (see [TaskOutcomeLabel])
 * @return one warning line per affected project, sorted by project path; empty when nothing qualifies
 */
internal fun buildReuseWarnings(outcomes: Map<String, String>): List<String> {
  val gateProjects =
    outcomes
      .filterKeys { koverTaskSimpleName(it) == "koverGateReport" }
      .filterValues { it in GATE_RAN_OUTCOMES }
      .keys
      .map { koverProjectOf(it) }
      .toSet()

  if (gateProjects.isEmpty()) return emptyList()

  val warnings = gateProjects.mapNotNull { project ->
    val reusedProducer =
      outcomes.entries.firstOrNull { (path, outcome) ->
        koverProjectOf(path) == project &&
          koverTaskSimpleName(path).startsWith("koverXmlReport") &&
          outcome in REUSED_OUTCOMES
      }

    reusedProducer?.let { entry ->
      "KoverGate freshness: the Kover XML for $project was reused (${entry.value}) this build while its " +
        "coverage gate ran; if the verdict looks wrong, re-run with --rerun-tasks to regenerate coverage."
    }
  }

  return warnings.sorted()
}
