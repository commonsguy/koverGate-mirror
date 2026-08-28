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
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoverGateReportTaskTest {

  // --- computePercent ---

  @Test
  fun `computePercent returns 100 when both covered and missed are 0`() {
    assertEquals(100.0, computePercent(covered = 0, missed = 0))
  }

  @Test
  fun `computePercent returns 100 for full coverage`() {
    assertEquals(100.0, computePercent(covered = 163, missed = 0))
  }

  @Test
  fun `computePercent rounds to one decimal half-up`() {
    // 161/163 = 98.7730... -> 98.8
    assertEquals(98.8, computePercent(covered = 161, missed = 2))
    // 108/110 = 98.1818... -> 98.2
    assertEquals(98.2, computePercent(covered = 108, missed = 2))
    // 1/3 = 33.3333... -> 33.3
    assertEquals(33.3, computePercent(covered = 1, missed = 2))
    // 2/3 = 66.6666... -> 66.7
    assertEquals(66.7, computePercent(covered = 2, missed = 1))
  }

  // --- computeMetricResult ---

  @Test
  fun `metric at exactly the threshold passes`() {
    val result = computeMetricResult(covered = 90, missed = 10, threshold = 90)
    assertTrue(result.passed)
    assertEquals(90.0, result.percent)
    assertEquals(90, result.threshold)
    assertEquals(90, result.covered)
    assertEquals(100, result.total)
  }

  @Test
  fun `metric one tenth below threshold fails`() {
    // 89/100 = 89.0 < 90
    val result = computeMetricResult(covered = 89, missed = 11, threshold = 90)
    assertFalse(result.passed)
  }

  @Test
  fun `metric above threshold passes`() {
    val result = computeMetricResult(covered = 95, missed = 5, threshold = 90)
    assertTrue(result.passed)
  }

  @Test
  fun `zero-division metric vacuously passes`() {
    val result = computeMetricResult(covered = 0, missed = 0, threshold = 100)
    assertTrue(result.passed)
    assertEquals(100.0, result.percent)
  }

  // --- sortFilesByInstructionLeverage ---

  @Test
  fun `sorts files by cold missed instructions descending`() {
    val files =
      listOf(
        FileCoverage("Low.kt", "p", listOf(1), missedInstructionsCold = 4),
        FileCoverage("High.kt", "p", listOf(1), missedInstructionsCold = 43),
      )

    val sorted = sortFilesByInstructionLeverage(files)

    assertEquals(listOf("High.kt", "Low.kt"), sorted.map { it.name })
  }

  @Test
  fun `breaks cold ties by residual descending`() {
    val files =
      listOf(
        FileCoverage("LessResidual.kt", "p", listOf(1), missedInstructionsCold = 10, missedInstructionsResidual = 2),
        FileCoverage("MoreResidual.kt", "p", listOf(1), missedInstructionsCold = 10, missedInstructionsResidual = 27),
      )

    val sorted = sortFilesByInstructionLeverage(files)

    assertEquals(listOf("MoreResidual.kt", "LessResidual.kt"), sorted.map { it.name })
  }

  @Test
  fun `breaks cold and residual ties by name ascending`() {
    val files =
      listOf(
        FileCoverage("Zebra.kt", "p", listOf(1), missedInstructionsCold = 5),
        FileCoverage("Alpha.kt", "p", listOf(1), missedInstructionsCold = 5),
      )

    val sorted = sortFilesByInstructionLeverage(files)

    assertEquals(listOf("Alpha.kt", "Zebra.kt"), sorted.map { it.name })
  }

  // --- buildConsoleOutput ---

  @Test
  fun `console output shows PASSED header when all metrics pass`() {
    val results =
      mapOf(
        CoverageMetric.LINE to MetricResult(163, 163, 100.0, 100, true),
        CoverageMetric.BRANCH to MetricResult(110, 110, 100.0, 100, true),
      )
    val output = buildConsoleOutput(":my-module", results, emptyList(), passed = true)
    assertTrue(output.contains("Coverage gate PASSED for :my-module"))
    assertTrue(output.contains("[PASS]"))
    assertFalse(output.contains("[FAIL]"))
  }

  @Test
  fun `console output shows FAILED header when any metric fails`() {
    val results =
      mapOf(
        CoverageMetric.LINE to MetricResult(161, 163, 98.8, 100, false),
        CoverageMetric.BRANCH to MetricResult(110, 110, 100.0, 100, true),
      )
    val output = buildConsoleOutput(":my-module", results, emptyList(), passed = false)
    assertTrue(output.contains("Coverage gate FAILED for :my-module"))
    assertTrue(output.contains("[FAIL]"))
  }

  @Test
  fun `console output includes min threshold for failing metrics`() {
    val results = mapOf(CoverageMetric.LINE to MetricResult(161, 163, 98.8, 100, false))
    val output = buildConsoleOutput(":m", results, emptyList(), passed = false)
    assertTrue(output.contains("[min: 100%]"))
  }

  @Test
  fun `console output does not include min threshold for passing metrics`() {
    val results = mapOf(CoverageMetric.LINE to MetricResult(163, 163, 100.0, 100, true))
    val output = buildConsoleOutput(":m", results, emptyList(), passed = true)
    assertFalse(output.contains("[min:"))
  }

  @Test
  fun `console output lists files with gaps`() {
    val results = mapOf(CoverageMetric.LINE to MetricResult(161, 163, 98.8, 100, false))
    val files =
      listOf(
        FileCoverage(
          "Foo.kt",
          "com/example",
          listOf(45, 47),
          listOf(BranchLine(45, 1, 2)),
          missedBranches = 1,
          totalBranches = 2,
        ),
        FileCoverage("Bar.kt", "com/example", listOf(112), emptyList()),
      )
    val output = buildConsoleOutput(":m", results, files, passed = false)
    assertTrue(output.contains("Files with gaps (most missed instructions first):"))
    assertTrue(output.contains("Foo.kt"))
    assertTrue(output.contains("Lines 45, 47 uncovered"))
    assertTrue(output.contains("Branches 45 (1/2) missed (1 of 2)"))
    assertTrue(output.contains("Bar.kt"))
    assertTrue(output.contains("Line 112 uncovered"))
  }

  @Test
  fun `console output uses singular Line for single uncovered line`() {
    val files = listOf(FileCoverage("X.kt", "p", listOf(10), emptyList()))
    val output = buildConsoleOutput(":m", emptyMap(), files, passed = false)
    assertTrue(output.contains("Line 10 uncovered"))
    assertFalse(output.contains("Lines 10 uncovered"))
  }

  @Test
  fun `disabled metric is absent from console output`() {
    // Simulate plugin excluding BRANCH from metricResults before calling
    // buildConsoleOutput
    val results =
      mapOf(
        CoverageMetric.LINE to MetricResult(100, 100, 100.0, 100, true)
        // BRANCH absent — as it would be if disabled
      )
    val output = buildConsoleOutput(":m", results, emptyList(), passed = true)
    assertTrue(output.contains("Line:"))
    assertFalse(output.contains("Branch:"), "Disabled metric must not appear in console output")
  }

  @Test
  fun `console output heading notes leverage ordering`() {
    val files = listOf(FileCoverage("Foo.kt", "com/example", listOf(45), missedInstructionsCold = 3))
    val output = buildConsoleOutput(":m", emptyMap(), files, passed = false)
    assertTrue(output.contains("Files with gaps (most missed instructions first):"))
  }

  @Test
  fun `console output annotates a file with only cold missed instructions`() {
    val files = listOf(FileCoverage("Foo.kt", "com/example", listOf(45), missedInstructionsCold = 43))
    val output = buildConsoleOutput(":m", emptyMap(), files, passed = false)
    assertTrue(output.contains("Foo.kt  [43 cold]"))
  }

  @Test
  fun `console output annotates a file with cold and residual missed instructions`() {
    val files =
      listOf(
        FileCoverage(
          "Foo.kt",
          "com/example",
          listOf(45),
          partiallyCoveredLines = listOf(60),
          missedInstructionsCold = 4,
          missedInstructionsResidual = 27,
        )
      )
    val output = buildConsoleOutput(":m", emptyMap(), files, passed = false)
    assertTrue(output.contains("Foo.kt  [4 cold, 27 residual]"))
  }

  @Test
  fun `console output labels a branch-arms-only file`() {
    val files =
      listOf(
        FileCoverage(
          "Foo.kt",
          "com/example",
          emptyList(),
          listOf(BranchLine(30, 1, 2)),
          missedBranches = 1,
          totalBranches = 6,
        )
      )
    val output = buildConsoleOutput(":m", emptyMap(), files, passed = false)
    assertTrue(output.contains("Foo.kt  [branch arms only]"))
  }

  @Test
  fun `console output omits the bracket when there is nothing to annotate`() {
    val files = listOf(FileCoverage("Foo.kt", "com/example", listOf(45)))
    val output = buildConsoleOutput(":m", emptyMap(), files, passed = false)
    assertTrue(output.contains("  Foo.kt\n"))
    assertFalse(output.contains("[]"))
  }

  // --- buildJsonOutput ---

  private fun parseJson(json: String): GapReport = Json.decodeFromString(json)

  @Test
  fun `JSON output contains correct module path`() {
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":my-module",
            passed = true,
            metricResults = emptyMap(),
            filesWithGaps = emptyList(),
            prettyPrint = false,
          )
        )
      )
    assertEquals(":my-module", report.module)
  }

  @Test
  fun `JSON passed field is true when all pass`() {
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = true,
            metricResults = emptyMap(),
            filesWithGaps = emptyList(),
            prettyPrint = false,
          )
        )
      )
    assertTrue(report.passed)
  }

  @Test
  fun `JSON passed field is false when any fail`() {
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = false,
            metricResults = emptyMap(),
            filesWithGaps = emptyList(),
            prettyPrint = false,
          )
        )
      )
    assertFalse(report.passed)
  }

  @Test
  fun `JSON metrics block contains correct fields`() {
    val results =
      mapOf(
        CoverageMetric.LINE to MetricResult(covered = 161, total = 163, percent = 98.8, threshold = 100, passed = false)
      )
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = false,
            metricResults = results,
            filesWithGaps = emptyList(),
            prettyPrint = false,
          )
        )
      )
    val metric = report.metrics["LINE"]
    assertNotNull(metric)
    assertEquals(161, metric!!.covered)
    assertEquals(163, metric.total)
    assertEquals(98.8, metric.percent)
    assertEquals(100, metric.threshold)
    assertFalse(metric.passed)
  }

  @Test
  fun `JSON files array contains gap files`() {
    val files = listOf(FileCoverage("Foo.kt", "com/example", listOf(45, 47), listOf(BranchLine(45, 1, 2))))
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = false,
            metricResults = emptyMap(),
            filesWithGaps = files,
            prettyPrint = false,
          )
        )
      )
    assertEquals(1, report.files.size)
    val file = report.files[0]
    assertEquals("Foo.kt", file.name)
    assertEquals("com/example", file.packageName)
    assertEquals(listOf(45, 47), file.uncoveredLines)
    assertEquals(listOf(BranchGap(45, 1, 2)), file.missedBranchLines)
  }

  @Test
  fun `JSON files array is empty when no gaps`() {
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = true,
            metricResults = emptyMap(),
            filesWithGaps = emptyList(),
            prettyPrint = false,
          )
        )
      )
    assertTrue(report.files.isEmpty())
  }

  @Test
  fun `disabled metric is absent from JSON metrics block`() {
    val results =
      mapOf(
        CoverageMetric.LINE to MetricResult(covered = 100, total = 100, percent = 100.0, threshold = 100, passed = true)
        // BRANCH absent — simulates it being disabled
      )
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = true,
            metricResults = results,
            filesWithGaps = emptyList(),
            prettyPrint = false,
          )
        )
      )
    assertNotNull(report.metrics["LINE"], "Enabled metric must appear in JSON")
    assertNull(report.metrics["BRANCH"], "Disabled metric must not appear in JSON")
  }

  @Test
  fun `prettyPrint=true produces multi-line indented output`() {
    val results =
      mapOf(
        CoverageMetric.LINE to MetricResult(covered = 163, total = 163, percent = 100.0, threshold = 100, passed = true)
      )
    val json =
      buildJsonOutput(
        JsonOutputParams(
          projectPath = ":m",
          passed = true,
          metricResults = results,
          filesWithGaps = emptyList(),
          prettyPrint = true,
        )
      )
    assertTrue(json.contains("\n"), "Pretty output must contain newlines")
    assertTrue(json.contains("    "), "Pretty output must contain 4-space indentation")
    // Data must be preserved
    val report = parseJson(json)
    assertEquals(":m", report.module)
    assertTrue(report.passed)
  }

  @Test
  fun `prettyPrint=false produces compact single-line output`() {
    val results =
      mapOf(
        CoverageMetric.LINE to MetricResult(covered = 163, total = 163, percent = 100.0, threshold = 100, passed = true)
      )
    val json =
      buildJsonOutput(
        JsonOutputParams(
          projectPath = ":m",
          passed = true,
          metricResults = results,
          filesWithGaps = emptyList(),
          prettyPrint = false,
        )
      )
    assertFalse(json.contains("\n"), "Compact output must not contain newlines")
    val report = parseJson(json)
    assertEquals(":m", report.module)
  }

  @Test
  fun `JSON files array includes partially covered lines`() {
    val files = listOf(FileCoverage("Foo.kt", "com/example", listOf(45), partiallyCoveredLines = listOf(60, 61)))
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = false,
            metricResults = emptyMap(),
            filesWithGaps = files,
            prettyPrint = false,
          )
        )
      )

    assertEquals(listOf(60, 61), report.files[0].partiallyCoveredLines)
  }

  @Test
  fun `JSON missed branch carries fullyInstructionCovered flag`() {
    val files =
      listOf(
        FileCoverage("Foo.kt", "com/example", emptyList(), listOf(BranchLine(42, 1, 2, fullyInstructionCovered = true)))
      )
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = false,
            metricResults = emptyMap(),
            filesWithGaps = files,
            prettyPrint = false,
          )
        )
      )

    assertTrue(report.files[0].missedBranchLines[0].fullyInstructionCovered)
  }

  @Test
  fun `console output lists partially covered lines`() {
    val files = listOf(FileCoverage("Foo.kt", "com/example", emptyList(), partiallyCoveredLines = listOf(36, 37)))
    val output = buildConsoleOutput(":m", emptyMap(), files, passed = false)

    assertTrue(output.contains("Lines 36, 37 partially covered"))
  }

  @Test
  fun `JSON files array includes missed instruction cold and residual counts`() {
    val files =
      listOf(
        FileCoverage("Foo.kt", "com/example", listOf(45), missedInstructionsCold = 43, missedInstructionsResidual = 7)
      )
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = false,
            metricResults = emptyMap(),
            filesWithGaps = files,
            prettyPrint = false,
          )
        )
      )

    assertEquals(43, report.files[0].missedInstructionsCold)
    assertEquals(7, report.files[0].missedInstructionsResidual)
  }

  @Test
  fun `JSON files array includes gapKind and dominantGapKind`() {
    val files =
      listOf(
        FileCoverage(
          "Foo.kt",
          "com/example",
          uncoveredLines = listOf(45),
          partiallyCoveredLines = listOf(50),
          missedInstructionsCold = 43,
          missedInstructionsResidual = 7,
        )
      )
    val report =
      parseJson(
        buildJsonOutput(
          JsonOutputParams(
            projectPath = ":m",
            passed = false,
            metricResults = emptyMap(),
            filesWithGaps = files,
            prettyPrint = false,
          )
        )
      )

    assertEquals(GapKind.MIXED, report.files[0].gapKind)
    assertEquals(GapKind.COLD, report.files[0].dominantGapKind)
  }
}
