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

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoverGateBumpTaskTest {

  private fun metric(percent: Double, threshold: Int): MetricJson =
    MetricJson(covered = 0, total = 0, percent = percent, threshold = threshold, passed = percent >= threshold)

  private fun reportOf(vararg entries: Pair<CoverageMetric, MetricJson>): GapReport =
    GapReport(
      module = ":app",
      passed = true,
      metrics = entries.associate { (metric, json) -> metric.name to json },
      files = emptyList(),
    )

  @Test
  fun `parseMargin reads a non-negative integer`() {
    assertEquals(0, parseMargin("0"))
    assertEquals(5, parseMargin("5"))
  }

  @Test
  fun `parseMargin uses the absolute value of a negative integer`() {
    assertEquals(7, parseMargin("-7"))
  }

  @Test
  fun `parseMargin rejects a non-integer`() {
    assertThrows(GradleException::class.java) { parseMargin("abc") }
  }

  @Test
  fun `suggests an increase when the coverage floor exceeds the threshold`() {
    val report = reportOf(CoverageMetric.LINE to metric(percent = 92.5, threshold = 85))

    val outcome = computeSuggestions(report, margin = 0, allowLower = false)

    assertEquals(listOf(GateSuggestion(CoverageMetric.LINE, current = 85, suggested = 92)), outcome.emitted)
    assertEquals(0, outcome.suppressedReductions)
  }

  @Test
  fun `skips a metric whose floor equals its threshold`() {
    val report = reportOf(CoverageMetric.LINE to metric(percent = 85.0, threshold = 85))

    val outcome = computeSuggestions(report, margin = 0, allowLower = false)

    assertTrue(outcome.emitted.isEmpty())
    assertEquals(0, outcome.suppressedReductions)
  }

  @Test
  fun `withholds a reduction when allowLower is false`() {
    val report = reportOf(CoverageMetric.LINE to metric(percent = 80.4, threshold = 90))

    val outcome = computeSuggestions(report, margin = 0, allowLower = false)

    assertTrue(outcome.emitted.isEmpty())
    assertEquals(1, outcome.suppressedReductions)
  }

  @Test
  fun `emits a reduction when allowLower is true`() {
    val report = reportOf(CoverageMetric.LINE to metric(percent = 80.4, threshold = 90))

    val outcome = computeSuggestions(report, margin = 0, allowLower = true)

    assertEquals(listOf(GateSuggestion(CoverageMetric.LINE, current = 90, suggested = 80)), outcome.emitted)
    assertEquals(0, outcome.suppressedReductions)
  }

  @Test
  fun `margin subtracts headroom from the suggested threshold`() {
    val report = reportOf(CoverageMetric.LINE to metric(percent = 92.5, threshold = 85))

    val outcome = computeSuggestions(report, margin = 5, allowLower = false)

    assertEquals(listOf(GateSuggestion(CoverageMetric.LINE, current = 85, suggested = 87)), outcome.emitted)
  }

  @Test
  fun `margin cannot push a suggestion below zero`() {
    val report = reportOf(CoverageMetric.LINE to metric(percent = 2.0, threshold = 10))

    val outcome = computeSuggestions(report, margin = 5, allowLower = true)

    assertEquals(listOf(GateSuggestion(CoverageMetric.LINE, current = 10, suggested = 0)), outcome.emitted)
  }

  @Test
  fun `skips metrics absent from the report`() {
    val report = reportOf(CoverageMetric.LINE to metric(percent = 90.0, threshold = 80))

    val outcome = computeSuggestions(report, margin = 0, allowLower = false)

    assertEquals(1, outcome.emitted.size)
    assertEquals(CoverageMetric.LINE, outcome.emitted.single().metric)
  }

  @Test
  fun `emits suggestions in metric declaration order`() {
    val report =
      reportOf(
        CoverageMetric.CLASS to metric(percent = 95.0, threshold = 80),
        CoverageMetric.LINE to metric(percent = 95.0, threshold = 80),
      )

    val outcome = computeSuggestions(report, margin = 0, allowLower = false)

    assertEquals(listOf(CoverageMetric.LINE, CoverageMetric.CLASS), outcome.emitted.map { it.metric })
  }

  @Test
  fun `render emits a set call annotated with the prior value`() {
    val outcome = SuggestionOutcome(listOf(GateSuggestion(CoverageMetric.LINE, current = 85, suggested = 92)), 0)

    val output = renderSuggestions(":app", outcome)

    assertTrue(output.contains("# koverGate: suggested threshold updates for :app"))
    assertTrue(output.contains("minLineCoverage.set(92)"))
    assertTrue(output.contains("// was 85"))
  }

  @Test
  fun `render reports no changes when nothing is emitted`() {
    val outcome = SuggestionOutcome(emitted = emptyList(), suppressedReductions = 0)

    val output = renderSuggestions(":app", outcome)

    assertTrue(output.contains("# No threshold changes suggested."))
    assertFalse(output.contains("--allow-lower"))
  }

  @Test
  fun `render hints at allow-lower when reductions were withheld`() {
    val outcome = SuggestionOutcome(emitted = emptyList(), suppressedReductions = 2)

    val output = renderSuggestions(":app", outcome)

    assertTrue(output.contains("2 metric(s) would lower a threshold"))
    assertTrue(output.contains("--allow-lower"))
  }

  @Test
  fun `render maps every metric to its DSL property name`() {
    val outcome =
      SuggestionOutcome(
        listOf(
          GateSuggestion(CoverageMetric.LINE, 1, 2),
          GateSuggestion(CoverageMetric.BRANCH, 1, 2),
          GateSuggestion(CoverageMetric.INSTRUCTION, 1, 2),
          GateSuggestion(CoverageMetric.METHOD, 1, 2),
          GateSuggestion(CoverageMetric.CLASS, 1, 2),
        ),
        0,
      )

    val output = renderSuggestions(":app", outcome)

    assertTrue(output.contains("minLineCoverage.set(2)"))
    assertTrue(output.contains("minBranchCoverage.set(2)"))
    assertTrue(output.contains("minInstructionCoverage.set(2)"))
    assertTrue(output.contains("minMethodCoverage.set(2)"))
    assertTrue(output.contains("minClassCoverage.set(2)"))
  }
}
