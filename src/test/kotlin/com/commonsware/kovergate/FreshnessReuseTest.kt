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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FreshnessReuseTest {

  // --- koverTaskSimpleName / koverProjectOf ---

  @Test
  fun `koverTaskSimpleName returns the segment after the last colon`() {
    assertEquals("koverGateReport", koverTaskSimpleName(":a:b:koverGateReport"))
  }

  @Test
  fun `koverProjectOf returns the owning project path`() {
    assertEquals(":a", koverProjectOf(":a:koverGateReport"))
  }

  @Test
  fun `koverProjectOf returns root for a top-level task`() {
    assertEquals(":", koverProjectOf(":koverGateReport"))
  }

  // --- buildReuseWarnings ---

  @Test
  fun `buildReuseWarnings warns when a gate ran while its producer was up-to-date`() {
    val outcomes = mapOf(":koverXmlReportJvm" to "UP_TO_DATE", ":koverGateReport" to "FAILED")

    val warnings = buildReuseWarnings(outcomes)

    assertEquals(1, warnings.size)
    assertTrue(warnings.first().contains("reused"))
    assertTrue(warnings.first().contains("--rerun-tasks"))
  }

  @Test
  fun `buildReuseWarnings is silent when the producer executed`() {
    val outcomes = mapOf(":koverXmlReportJvm" to "EXECUTED", ":koverGateReport" to "FAILED")

    assertTrue(buildReuseWarnings(outcomes).isEmpty())
  }

  @Test
  fun `buildReuseWarnings is silent when the gate did not run`() {
    val outcomes = mapOf(":koverXmlReportJvm" to "UP_TO_DATE", ":koverGateReport" to "UP_TO_DATE")

    assertTrue(buildReuseWarnings(outcomes).isEmpty())
  }

  @Test
  fun `buildReuseWarnings scopes producers and gates to the same project`() {
    val outcomes =
      mapOf(
        ":a:koverXmlReportJvm" to "UP_TO_DATE",
        ":a:koverGateReport" to "FAILED",
        ":b:koverXmlReportJvm" to "EXECUTED",
        ":b:koverGateReport" to "FAILED",
      )

    val warnings = buildReuseWarnings(outcomes)

    assertEquals(1, warnings.size)
    assertTrue(warnings.first().contains(":a"))
  }
}
