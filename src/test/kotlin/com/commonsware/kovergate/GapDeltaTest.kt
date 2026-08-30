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

class GapDeltaTest {

  private fun report(metrics: Map<String, MetricJson> = emptyMap(), files: List<FileGap> = emptyList()) =
    GapReport(module = ":app", passed = true, metrics = metrics, files = files)

  @Test
  fun `null baseline yields ABSENT with empty metrics and files`() {
    val current = report(metrics = mapOf("LINE" to MetricJson(80, 100, 80.0, 90, false)))

    val delta = computeGapDelta(baseline = null, current = current)

    assertEquals(BaselineStatus.ABSENT, delta.baseline)
    assertEquals(":app", delta.module)
    assertTrue(delta.metrics.isEmpty())
    assertTrue(delta.files.isEmpty())
  }

  @Test
  fun `metric present in both reports produces a delta`() {
    val baseline = report(metrics = mapOf("LINE" to MetricJson(80, 100, 80.0, 90, false)))
    val current = report(metrics = mapOf("LINE" to MetricJson(90, 100, 90.0, 90, true)))

    val delta = computeGapDelta(baseline, current)

    assertEquals(BaselineStatus.PRESENT, delta.baseline)
    assertEquals(
      MetricDelta(percentDelta = 10.0, coveredDelta = 10, totalDelta = 0, headroomDelta = 10, headroom = 0),
      delta.metrics["LINE"],
    )
  }

  @Test
  fun `headroom and headroomDelta are computed from covered, total, and threshold, not read from a stored field`() {
    val baseline = report(metrics = mapOf("LINE" to MetricJson(179, 200, 89.5, 90, false)))
    val current = report(metrics = mapOf("LINE" to MetricJson(180, 200, 90.0, 90, true)))

    val delta = computeGapDelta(baseline, current)

    val lineDelta = delta.metrics.getValue("LINE")

    assertEquals(0, lineDelta.headroom)
    assertEquals(1, lineDelta.headroomDelta)
  }

  @Test
  fun `headroomDelta and headroom are null when the current metric has zero total`() {
    val baseline = report(metrics = mapOf("LINE" to MetricJson(90, 100, 90.0, 90, true)))
    val current = report(metrics = mapOf("LINE" to MetricJson(0, 0, 0.0, 90, true)))

    val delta = computeGapDelta(baseline, current)

    val lineDelta = delta.metrics.getValue("LINE")

    assertEquals(null, lineDelta.headroom)
    assertEquals(null, lineDelta.headroomDelta)
  }

  @Test
  fun `headroomDelta is null when the baseline metric has zero total, but headroom still reflects current`() {
    val baseline = report(metrics = mapOf("LINE" to MetricJson(0, 0, 0.0, 90, true)))
    val current = report(metrics = mapOf("LINE" to MetricJson(90, 100, 90.0, 90, true)))

    val delta = computeGapDelta(baseline, current)

    val lineDelta = delta.metrics.getValue("LINE")

    assertEquals(0, lineDelta.headroom)
    assertEquals(null, lineDelta.headroomDelta)
  }

  @Test
  fun `headroomDelta conflates a threshold change between builds with the coverage change`() {
    val baseline = report(metrics = mapOf("LINE" to MetricJson(90, 100, 90.0, 80, true)))
    val current = report(metrics = mapOf("LINE" to MetricJson(90, 100, 90.0, 95, false)))

    val delta = computeGapDelta(baseline, current)

    val lineDelta = delta.metrics.getValue("LINE")

    assertEquals(0, lineDelta.coveredDelta)
    assertEquals(-5, lineDelta.headroom)
    assertEquals(-15, lineDelta.headroomDelta)
  }

  @Test
  fun `metric present in only one report is omitted`() {
    val baseline = report(metrics = mapOf("LINE" to MetricJson(80, 100, 80.0, 90, false)))
    val current =
      report(
        metrics = mapOf("LINE" to MetricJson(90, 100, 90.0, 90, true), "BRANCH" to MetricJson(50, 100, 50.0, 90, false))
      )

    val delta = computeGapDelta(baseline, current)

    assertEquals(setOf("LINE"), delta.metrics.keys)
  }

  @Test
  fun `percentDelta is re-rounded to one decimal place`() {
    val baseline = report(metrics = mapOf("LINE" to MetricJson(873, 1000, 87.3, 90, false)))
    val current = report(metrics = mapOf("LINE" to MetricJson(851, 1000, 85.1, 90, false)))

    val delta = computeGapDelta(baseline, current)

    assertEquals(-2.2, delta.metrics.getValue("LINE").percentDelta, 0.0)
  }

  @Test
  fun `file present in both reports produces a field-by-field delta`() {
    val baseline =
      report(
        files =
          listOf(
            FileGap(
              "Foo.kt",
              "com/example",
              uncoveredLines = listOf(1, 2),
              missedBranchLines = emptyList(),
              partiallyCoveredLines = listOf(3),
              missedInstructionsCold = 10,
              missedInstructionsResidual = 5,
            )
          )
      )
    val current =
      report(
        files =
          listOf(
            FileGap(
              "Foo.kt",
              "com/example",
              uncoveredLines = listOf(1, 2, 3),
              missedBranchLines = listOf(BranchGap(4, 1, 2)),
              partiallyCoveredLines = emptyList(),
              missedInstructionsCold = 18,
              missedInstructionsResidual = 5,
            )
          )
      )

    val delta = computeGapDelta(baseline, current)

    assertEquals(1, delta.files.size)

    val fileDelta = delta.files[0]

    assertEquals("Foo.kt", fileDelta.name)
    assertEquals("com/example", fileDelta.packageName)
    assertEquals(8, fileDelta.missedInstructionsColdDelta)
    assertEquals(0, fileDelta.missedInstructionsResidualDelta)
    assertEquals(1, fileDelta.uncoveredLineCountDelta)
    assertEquals(-1, fileDelta.partiallyCoveredLineCountDelta)
    assertEquals(1, fileDelta.missedBranchLineCountDelta)
    assertEquals(DeltaStatus.WORSENED, fileDelta.status)
  }

  @Test
  fun `file present only in current report has deltas equal to the current values`() {
    val baseline = report()
    val current =
      report(
        files =
          listOf(
            FileGap(
              "New.kt",
              "com/example",
              uncoveredLines = listOf(1, 2),
              missedBranchLines = emptyList(),
              missedInstructionsCold = 20,
              missedInstructionsResidual = 0,
            )
          )
      )

    val delta = computeGapDelta(baseline, current)

    assertEquals(1, delta.files.size)
    assertEquals(20, delta.files[0].missedInstructionsColdDelta)
    assertEquals(2, delta.files[0].uncoveredLineCountDelta)
    assertEquals(DeltaStatus.WORSENED, delta.files[0].status)
  }

  @Test
  fun `file present only in baseline report has deltas equal to the negated baseline values`() {
    val baseline =
      report(
        files =
          listOf(
            FileGap(
              "Fixed.kt",
              "com/example",
              uncoveredLines = listOf(1, 2),
              missedBranchLines = emptyList(),
              missedInstructionsCold = 20,
              missedInstructionsResidual = 0,
            )
          )
      )
    val current = report()

    val delta = computeGapDelta(baseline, current)

    assertEquals(1, delta.files.size)
    assertEquals(-20, delta.files[0].missedInstructionsColdDelta)
    assertEquals(-2, delta.files[0].uncoveredLineCountDelta)
    assertEquals(DeltaStatus.IMPROVED, delta.files[0].status)
  }

  @Test
  fun `a file with no change in any count is omitted`() {
    val gap =
      FileGap(
        "Same.kt",
        "com/example",
        uncoveredLines = listOf(1),
        missedBranchLines = emptyList(),
        missedInstructionsCold = 5,
        missedInstructionsResidual = 0,
      )
    val baseline = report(files = listOf(gap))
    val current = report(files = listOf(gap))

    val delta = computeGapDelta(baseline, current)

    assertTrue(delta.files.isEmpty())
  }

  @Test
  fun `null missedInstructionsCold and missedInstructionsResidual are treated as zero`() {
    val baseline = report()
    val current =
      report(
        files =
          listOf(
            FileGap(
              "Ghost.kt",
              "com/example",
              uncoveredLines = listOf(1),
              missedBranchLines = emptyList(),
              missedInstructionsCold = null,
              missedInstructionsResidual = null,
            )
          )
      )

    val delta = computeGapDelta(baseline, current)

    assertEquals(1, delta.files.size)
    assertEquals(0, delta.files[0].missedInstructionsColdDelta)
    assertEquals(0, delta.files[0].missedInstructionsResidualDelta)
    assertEquals(1, delta.files[0].uncoveredLineCountDelta)
    assertEquals(DeltaStatus.IMPROVED, delta.files[0].status)
  }

  @Test
  fun `a file whose instruction sum is unchanged but whose line counts moved is classified IMPROVED`() {
    val baseline =
      report(
        files =
          listOf(
            FileGap(
              "Shifted.kt",
              "com/example",
              uncoveredLines = listOf(1, 2),
              missedBranchLines = emptyList(),
              missedInstructionsCold = 10,
              missedInstructionsResidual = 0,
            )
          )
      )
    val current =
      report(
        files =
          listOf(
            FileGap(
              "Shifted.kt",
              "com/example",
              uncoveredLines = listOf(1, 2, 3),
              missedBranchLines = emptyList(),
              missedInstructionsCold = 10,
              missedInstructionsResidual = 0,
            )
          )
      )

    val delta = computeGapDelta(baseline, current)

    assertEquals(1, delta.files.size)
    assertEquals(0, delta.files[0].missedInstructionsColdDelta)
    assertEquals(0, delta.files[0].missedInstructionsResidualDelta)
    assertEquals(1, delta.files[0].uncoveredLineCountDelta)
    assertEquals(DeltaStatus.IMPROVED, delta.files[0].status)
  }
}
