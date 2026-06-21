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

import java.time.Instant

/**
 * Converts an epoch-milliseconds timestamp to an ISO-8601 instant string in UTC (e.g. `2026-06-18T14:03:11Z`).
 *
 * @param epochMillis the timestamp in milliseconds since the Unix epoch
 * @return the ISO-8601 UTC representation of the instant
 */
internal fun isoInstant(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

/**
 * Computes [SourceProvenance] for a gap report from raw timestamps. Pure and Gradle-free so it can be unit-tested
 * directly. The report is considered stale when the newest build-script timestamp is strictly greater than the XML
 * report's timestamp, meaning the on-disk coverage may predate the current build configuration.
 *
 * @param xmlPath absolute path of the Kover XML report the gap report was computed from
 * @param xmlModifiedEpochMs the XML report's last-modified time in epoch milliseconds
 * @param buildScriptEpochsMs last-modified times, in epoch milliseconds, of the module's build scripts (may be empty)
 * @return the computed provenance, with [SourceProvenance.stale] set per the rule above
 */
internal fun computeProvenance(
  xmlPath: String,
  xmlModifiedEpochMs: Long,
  buildScriptEpochsMs: List<Long>,
): SourceProvenance {
  val newest = buildScriptEpochsMs.maxOrNull()
  val stale = newest != null && newest > xmlModifiedEpochMs

  return SourceProvenance(
    xmlPath = xmlPath,
    xmlModified = isoInstant(xmlModifiedEpochMs),
    stale = stale,
    newestBuildScriptModified = newest?.let { isoInstant(it) },
  )
}

/**
 * Builds the human-readable freshness warning for a provenance record, or returns `null` when the report is not stale.
 *
 * The message names the XML path, both timestamps, and the remedy (`--rerun-tasks`). It is intended for `logger.warn`;
 * a stale report is never a build failure, because the verdict was still computed faithfully from the XML on disk.
 *
 * @param provenance the provenance computed by [computeProvenance]
 * @return the warning text to log when [SourceProvenance.stale] is `true`, or `null` otherwise
 */
internal fun buildFreshnessWarning(provenance: SourceProvenance): String? {
  if (!provenance.stale) return null

  return buildString {
    appendLine("KoverGate: coverage report may be STALE (build-script timestamp heuristic).")
    appendLine("  Kover XML: ${provenance.xmlPath}")
    appendLine("  XML modified:          ${provenance.xmlModified}")
    appendLine("  Build script modified: ${provenance.newestBuildScriptModified}")
    appendLine("  A build script is newer than the Kover XML, so coverage may predate your current config.")
    append("  This is a timestamp heuristic, not a guarantee; re-run with --rerun-tasks to regenerate coverage.")
  }
}
