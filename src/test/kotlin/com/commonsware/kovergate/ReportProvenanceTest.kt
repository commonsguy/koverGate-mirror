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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportProvenanceTest {

  // --- isoInstant ---

  @Test
  fun `isoInstant formats epoch millis as ISO-8601 UTC`() {
    assertEquals("1970-01-01T00:00:00Z", isoInstant(0))
  }

  // --- computeProvenance ---

  @Test
  fun `computeProvenance flags stale when a build script is newer than the XML`() {
    val provenance =
      computeProvenance("/p/report.xml", xmlModifiedEpochMs = 1000, buildScriptEpochsMs = listOf(500, 2000))

    assertTrue(provenance.stale)
    assertEquals("/p/report.xml", provenance.xmlPath)
    assertEquals(isoInstant(2000), provenance.newestBuildScriptModified)
  }

  @Test
  fun `computeProvenance is not stale when the XML is newer than every build script`() {
    val provenance =
      computeProvenance("/p/report.xml", xmlModifiedEpochMs = 5000, buildScriptEpochsMs = listOf(500, 2000))

    assertFalse(provenance.stale)
  }

  @Test
  fun `computeProvenance is not stale when the XML equals the newest build script`() {
    val provenance = computeProvenance("/p/report.xml", xmlModifiedEpochMs = 2000, buildScriptEpochsMs = listOf(2000))

    assertFalse(provenance.stale)
  }

  @Test
  fun `computeProvenance is not stale and has null build-script time when no build scripts exist`() {
    val provenance = computeProvenance("/p/report.xml", xmlModifiedEpochMs = 1000, buildScriptEpochsMs = emptyList())

    assertFalse(provenance.stale)
    assertNull(provenance.newestBuildScriptModified)
  }

  // --- buildFreshnessWarning ---

  @Test
  fun `buildFreshnessWarning returns null when not stale`() {
    val provenance =
      SourceProvenance("/p/report.xml", isoInstant(2000), stale = false, newestBuildScriptModified = isoInstant(1000))

    assertNull(buildFreshnessWarning(provenance))
  }

  @Test
  fun `buildFreshnessWarning describes the staleness when stale`() {
    val provenance =
      SourceProvenance("/p/report.xml", isoInstant(1000), stale = true, newestBuildScriptModified = isoInstant(2000))

    val warning = buildFreshnessWarning(provenance)

    assertNotNull(warning)
    assertTrue(warning!!.contains("STALE"))
    assertTrue(warning.contains("/p/report.xml"))
    assertTrue(warning.contains("--rerun-tasks"))
  }

  @Test
  fun `buildFreshnessWarning labels staleness as a build-script heuristic`() {
    val provenance =
      SourceProvenance("/p/report.xml", isoInstant(1000), stale = true, newestBuildScriptModified = isoInstant(2000))

    val warning = buildFreshnessWarning(provenance)

    assertNotNull(warning)
    assertTrue(warning!!.contains("heuristic"))
  }

  // --- GapReport serialization ---

  @Test
  fun `GapReport round-trips the source provenance block`() {
    val report =
      GapReport(
        module = ":m",
        passed = true,
        metrics = emptyMap(),
        files = emptyList(),
        source = computeProvenance("/p/report.xml", 1000, listOf(2000)),
      )

    val json = Json.encodeToString(report)
    val decoded = Json.decodeFromString<GapReport>(json)

    assertEquals(report, decoded)
    assertNotNull(decoded.source)
    assertTrue(decoded.source!!.stale)
  }

  @Test
  fun `GapReport without source still decodes for backward compatibility`() {
    val legacy = """{"module":":m","passed":true,"metrics":{},"files":[]}"""

    val decoded = Json.decodeFromString<GapReport>(legacy)

    assertNull(decoded.source)
  }

  @Test
  fun `GapReport writes the scope block with ignoreSpaceChange even when false`() {
    val report =
      GapReport(
        module = ":m",
        passed = true,
        metrics = emptyMap(),
        files = emptyList(),
        scope = ScopeProvenance("branch", false),
      )

    val json = Json.encodeToString(report)

    assertTrue(json.contains(""""scope":{"type":"branch","ignoreSpaceChange":false}"""), "Got: $json")
    assertEquals(report, Json.decodeFromString<GapReport>(json))
  }

  @Test
  fun `GapReport without scope omits the scope key`() {
    val report = GapReport(module = ":m", passed = true, metrics = emptyMap(), files = emptyList())

    val json = Json.encodeToString(report)

    assertFalse(json.contains("\"scope\""), "Got: $json")
  }
}
