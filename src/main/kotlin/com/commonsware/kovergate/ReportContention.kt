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

private const val STAMP_FIELD_COUNT = 3

/**
 * The identity of a Kover XML report as recorded by its producer task immediately after that task finished.
 *
 * @property buildId the id of the build in which the producer task ran, used to detect a stamp left over from a
 *   previous build
 * @property xmlModifiedEpochMs the XML report's last-modified time, in epoch milliseconds, at producer completion
 * @property xmlSizeBytes the XML report's size, in bytes, at producer completion
 */
internal data class ProducerStamp(val buildId: String, val xmlModifiedEpochMs: Long, val xmlSizeBytes: Long)

/**
 * Formats a [ProducerStamp] as a human-readable, [parseStamp]-round-trippable line of text.
 *
 * @param stamp the stamp to format
 * @return the formatted stamp text
 */
internal fun formatStamp(stamp: ProducerStamp): String =
  "${stamp.buildId}:${stamp.xmlModifiedEpochMs}:${stamp.xmlSizeBytes}"

/**
 * Parses text written by [formatStamp] back into a [ProducerStamp].
 *
 * @param text the stamp file's contents
 * @return the parsed stamp, or `null` when [text] is absent, blank, or malformed
 */
internal fun parseStamp(text: String): ProducerStamp? {
  val fields = text.trim().split(':')

  if (fields.size != STAMP_FIELD_COUNT) return null

  val xmlModifiedEpochMs = fields[1].toLongOrNull()
  val xmlSizeBytes = fields[2].toLongOrNull()

  return if (xmlModifiedEpochMs != null && xmlSizeBytes != null) {
    ProducerStamp(buildId = fields[0], xmlModifiedEpochMs = xmlModifiedEpochMs, xmlSizeBytes = xmlSizeBytes)
  } else {
    null
  }
}

/**
 * The outcome of comparing a gate task's read-time XML identity against its producer's stamp.
 *
 * - [NO_STAMP]: no producer stamp exists (no known producer, or the producer was reused this build).
 * - [STALE_STAMP]: the stamp was written by a previous build, not this one.
 * - [UNCHANGED]: the XML's identity at read time matches the producer's stamp.
 * - [REWRITTEN]: the XML's identity at read time differs from the producer's stamp — a finding.
 */
internal enum class ContentionVerdict {
  NO_STAMP,
  STALE_STAMP,
  UNCHANGED,
  REWRITTEN,
}

/**
 * Compares a gate task's read-time XML identity against its producer's stamp to detect a mid-flight rewrite.
 *
 * Only [ContentionVerdict.REWRITTEN] is a finding; the other outcomes must stay silent (see the sketch's §3/§4).
 *
 * @param stamp the producer's stamp, or `null` when no producer is known or the producer was reused this build
 * @param currentBuildId the id of the build the gate task is running in
 * @param xmlModifiedEpochMs the XML report's last-modified time, in epoch milliseconds, at gate read time
 * @param xmlSizeBytes the XML report's size, in bytes, at gate read time
 * @return the resolved [ContentionVerdict]
 */
internal fun detectBeforeReadRewrite(
  stamp: ProducerStamp?,
  currentBuildId: String,
  xmlModifiedEpochMs: Long,
  xmlSizeBytes: Long,
): ContentionVerdict {
  if (stamp == null) return ContentionVerdict.NO_STAMP

  val unchanged = stamp.xmlModifiedEpochMs == xmlModifiedEpochMs && stamp.xmlSizeBytes == xmlSizeBytes

  return when {
    stamp.buildId != currentBuildId -> ContentionVerdict.STALE_STAMP
    unchanged -> ContentionVerdict.UNCHANGED
    else -> ContentionVerdict.REWRITTEN
  }
}

/**
 * The identity of a Kover XML report observed at a single point in time, used to keep [buildBeforeReadWarning]'s
 * parameter list short.
 *
 * @property modifiedEpochMs the XML report's last-modified time, in epoch milliseconds
 * @property sizeBytes the XML report's size, in bytes
 */
internal data class XmlIdentity(val modifiedEpochMs: Long, val sizeBytes: Long)

/**
 * Builds the human-readable warning for a before-read rewrite: the gate parsed a report written by some task other than
 * its declared producer, so this build's verdict may not reflect the module's own coverage configuration.
 *
 * @param projectPath the Gradle project path whose gate detected the rewrite
 * @param xmlPath the absolute path of the contended Kover XML report
 * @param producerTaskPath the fully-qualified path of the report's declared producer task
 * @param stamp the producer's stamp, as recorded when the producer finished
 * @param actual the XML report's identity at gate read time
 * @return the warning text, suitable for `logger.warn` or a `GradleException` message
 */
internal fun buildBeforeReadWarning(
  projectPath: String,
  xmlPath: String,
  producerTaskPath: String,
  stamp: ProducerStamp,
  actual: XmlIdentity,
): String = buildString {
  appendLine("KoverGate: coverage report for $projectPath was REWRITTEN before it was read; the verdict may be wrong.")
  appendLine("  Kover XML: $xmlPath")
  appendLine("  Declared producer: $producerTaskPath")
  appendLine("  Producer wrote:  modified=${isoInstant(stamp.xmlModifiedEpochMs)} size=${stamp.xmlSizeBytes}")
  appendLine("  Gate read:       modified=${isoInstant(actual.modifiedEpochMs)} size=${actual.sizeBytes}")
  append(
    "  Some other task wrote this path after $producerTaskPath finished; give the gate a report path " +
      "nothing else writes."
  )
}

/**
 * What a gate task observed about a Kover XML report at the moment it read it, carried forward so the build-end
 * freshness check can detect a rewrite that happened after the read.
 *
 * @property projectPath the Gradle project path whose gate made the observation
 * @property xmlPath the absolute path of the Kover XML report that was read
 * @property modifiedEpochMs the XML report's last-modified time, in epoch milliseconds, at read time
 * @property sizeBytes the XML report's size, in bytes, at read time
 */
internal data class GateReadObservation(
  val projectPath: String,
  val xmlPath: String,
  val modifiedEpochMs: Long,
  val sizeBytes: Long,
)

/**
 * Builds the human-readable warning for an after-read rewrite, or returns `null` when the report is unchanged.
 *
 * This build's verdict is unaffected — the gate read the producer's own output — but the report path is contended and a
 * future build may not be so lucky (see the sketch's §2/§5).
 *
 * @param observation what the gate observed when it read the report
 * @param currentModifiedEpochMs the XML report's last-modified time, in epoch milliseconds, at build end
 * @param currentSizeBytes the XML report's size, in bytes, at build end
 * @return the warning text, or `null` when the report's identity has not changed since the gate read it
 */
internal fun buildAfterReadWarning(
  observation: GateReadObservation,
  currentModifiedEpochMs: Long,
  currentSizeBytes: Long,
): String? {
  val unchanged = observation.modifiedEpochMs == currentModifiedEpochMs && observation.sizeBytes == currentSizeBytes

  if (unchanged) return null

  return buildString {
    appendLine(
      "KoverGate: coverage report for ${observation.projectPath} was rewritten after ${observation.projectPath}'s " +
        "gate read it; this build's verdict is unaffected, but the report path is contended."
    )
    appendLine("  Kover XML: ${observation.xmlPath}")
    appendLine("  Gate read:  modified=${isoInstant(observation.modifiedEpochMs)} size=${observation.sizeBytes}")
    append("  Now:        modified=${isoInstant(currentModifiedEpochMs)} size=$currentSizeBytes")
  }
}
