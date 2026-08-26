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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportContentionTest {

  // --- formatStamp / parseStamp ---

  @Test
  fun `parseStamp round-trips a formatted stamp`() {
    val stamp = ProducerStamp(buildId = "build-123", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    val parsed = parseStamp(formatStamp(stamp))

    assertEquals(stamp, parsed)
  }

  @Test
  fun `parseStamp returns null for malformed text`() {
    assertNull(parseStamp("not a stamp"))
  }

  @Test
  fun `parseStamp returns null for empty text`() {
    assertNull(parseStamp(""))
  }

  // --- detectBeforeReadRewrite ---

  @Test
  fun `detectBeforeReadRewrite is NO_STAMP when no stamp exists`() {
    val verdict =
      detectBeforeReadRewrite(stamp = null, currentBuildId = "build-123", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    assertEquals(ContentionVerdict.NO_STAMP, verdict)
  }

  @Test
  fun `detectBeforeReadRewrite is STALE_STAMP when the stamp is from a previous build`() {
    val stamp = ProducerStamp(buildId = "build-old", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    val verdict =
      detectBeforeReadRewrite(stamp = stamp, currentBuildId = "build-new", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    assertEquals(ContentionVerdict.STALE_STAMP, verdict)
  }

  @Test
  fun `detectBeforeReadRewrite is UNCHANGED when mtime and size both match`() {
    val stamp = ProducerStamp(buildId = "build-123", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    val verdict =
      detectBeforeReadRewrite(stamp = stamp, currentBuildId = "build-123", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    assertEquals(ContentionVerdict.UNCHANGED, verdict)
  }

  @Test
  fun `detectBeforeReadRewrite is REWRITTEN when mtime differs`() {
    val stamp = ProducerStamp(buildId = "build-123", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    val verdict =
      detectBeforeReadRewrite(stamp = stamp, currentBuildId = "build-123", xmlModifiedEpochMs = 2000, xmlSizeBytes = 42)

    assertEquals(ContentionVerdict.REWRITTEN, verdict)
  }

  @Test
  fun `detectBeforeReadRewrite is REWRITTEN when size differs`() {
    val stamp = ProducerStamp(buildId = "build-123", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    val verdict =
      detectBeforeReadRewrite(stamp = stamp, currentBuildId = "build-123", xmlModifiedEpochMs = 1000, xmlSizeBytes = 99)

    assertEquals(ContentionVerdict.REWRITTEN, verdict)
  }

  // --- buildBeforeReadWarning ---

  @Test
  fun `buildBeforeReadWarning names the project, xml path, and producer task`() {
    val stamp = ProducerStamp(buildId = "build-123", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    val warning =
      buildBeforeReadWarning(
        projectPath = ":a",
        xmlPath = "/p/report.xml",
        producerTaskPath = ":a:koverXmlReportJvm",
        stamp = stamp,
        actual = XmlIdentity(modifiedEpochMs = 2000, sizeBytes = 99),
      )

    assertTrue(warning.contains(":a"))
    assertTrue(warning.contains("/p/report.xml"))
    assertTrue(warning.contains(":a:koverXmlReportJvm"))
  }

  @Test
  fun `buildBeforeReadWarning says the verdict may be wrong`() {
    val stamp = ProducerStamp(buildId = "build-123", xmlModifiedEpochMs = 1000, xmlSizeBytes = 42)

    val warning =
      buildBeforeReadWarning(
        projectPath = ":a",
        xmlPath = "/p/report.xml",
        producerTaskPath = ":a:koverXmlReportJvm",
        stamp = stamp,
        actual = XmlIdentity(modifiedEpochMs = 2000, sizeBytes = 99),
      )

    assertTrue(warning.contains("may be wrong") || warning.contains("may not"))
  }

  // --- buildAfterReadWarning ---

  @Test
  fun `buildAfterReadWarning returns null when unchanged`() {
    val observation =
      GateReadObservation(projectPath = ":a", xmlPath = "/p/report.xml", modifiedEpochMs = 1000, sizeBytes = 42)

    val warning = buildAfterReadWarning(observation, currentModifiedEpochMs = 1000, currentSizeBytes = 42)

    assertNull(warning)
  }

  @Test
  fun `buildAfterReadWarning describes a contended path without impugning this build's verdict`() {
    val observation =
      GateReadObservation(projectPath = ":a", xmlPath = "/p/report.xml", modifiedEpochMs = 1000, sizeBytes = 42)

    val warning = buildAfterReadWarning(observation, currentModifiedEpochMs = 2000, currentSizeBytes = 42)

    assertNotNull(warning)
    assertTrue(warning!!.contains(":a"))
    assertTrue(warning.contains("/p/report.xml"))
    assertTrue(warning.contains("contended"))
  }
}
