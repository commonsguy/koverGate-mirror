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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoverGateDoctorTaskTest {

  private val allThresholds =
    mapOf(
      CoverageMetric.LINE to 100,
      CoverageMetric.BRANCH to 90,
      CoverageMetric.INSTRUCTION to 100,
      CoverageMetric.METHOD to 100,
      CoverageMetric.CLASS to 100,
    )

  @Test
  fun `doctor output shows the project path header`() {
    val output =
      buildDoctorOutput(
        DoctorOutputParams(
          projectPath = ":app",
          thresholds = allThresholds,
          disabledMetrics = emptySet(),
          xmlPath = "/p/report.xml",
          xmlExists = true,
          provenance = null,
          resolvedExcludes = emptyList(),
        )
      )

    assertTrue(output.contains("KoverGate doctor for :app"))
  }

  @Test
  fun `doctor output marks enabled and disabled metrics`() {
    val output =
      buildDoctorOutput(
        DoctorOutputParams(
          projectPath = ":app",
          thresholds = allThresholds,
          disabledMetrics = setOf(CoverageMetric.BRANCH),
          xmlPath = "/p/report.xml",
          xmlExists = true,
          provenance = null,
          resolvedExcludes = emptyList(),
        )
      )

    assertTrue(output.contains("LINE: 100% (enabled)"))
    assertTrue(output.contains("BRANCH: 90% (disabled)"))
  }

  @Test
  fun `doctor output reports freshness from provenance`() {
    val provenance =
      SourceProvenance("/p/report.xml", isoInstant(1000), stale = true, newestBuildScriptModified = isoInstant(2000))

    val output =
      buildDoctorOutput(
        DoctorOutputParams(
          projectPath = ":app",
          thresholds = allThresholds,
          disabledMetrics = emptySet(),
          xmlPath = "/p/report.xml",
          xmlExists = true,
          provenance = provenance,
          resolvedExcludes = emptyList(),
        )
      )

    assertTrue(output.contains("stale: true"))
    assertTrue(output.contains("modified: ${isoInstant(1000)}"))
  }

  @Test
  fun `doctor output reports a missing XML without provenance`() {
    val output =
      buildDoctorOutput(
        DoctorOutputParams(
          projectPath = ":app",
          thresholds = allThresholds,
          disabledMetrics = emptySet(),
          xmlPath = "/p/report.xml",
          xmlExists = false,
          provenance = null,
          resolvedExcludes = emptyList(),
        )
      )

    assertTrue(output.contains("exists: false"))
    assertTrue(output.contains("run koverGateGenerateReport"))
    assertFalse(output.contains("stale:"))
  }

  @Test
  fun `doctor output lists excluded packages`() {
    val output =
      buildDoctorOutput(
        DoctorOutputParams(
          projectPath = ":app",
          thresholds = allThresholds,
          disabledMetrics = emptySet(),
          xmlPath = "/p/report.xml",
          xmlExists = true,
          provenance = null,
          resolvedExcludes = listOf("com.example.conformance", "com.example.generated"),
        )
      )

    assertTrue(output.contains("com.example.conformance"))
    assertTrue(output.contains("com.example.generated"))
  }

  @Test
  fun `doctor output shows none when no excludes`() {
    val output =
      buildDoctorOutput(
        DoctorOutputParams(
          projectPath = ":app",
          thresholds = allThresholds,
          disabledMetrics = emptySet(),
          xmlPath = "/p/report.xml",
          xmlExists = true,
          provenance = null,
          resolvedExcludes = emptyList(),
        )
      )

    assertTrue(output.contains("(none)"))
  }
}
