package com.commonsware.kovergate

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KoverGapReportTaskTest {

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

    // --- buildConsoleOutput ---

    @Test
    fun `console output shows PASSED header when all metrics pass`() {
        val results = mapOf(
            CoverageMetric.LINE to MetricResult(163, 163, 100.0, 100, true),
            CoverageMetric.BRANCH to MetricResult(110, 110, 100.0, 100, true)
        )
        val output = buildConsoleOutput(":my-module", results, emptyList(), passed = true)
        assertTrue(output.contains("Coverage gate PASSED for :my-module"))
        assertTrue(output.contains("[PASS]"))
        assertFalse(output.contains("[FAIL]"))
    }

    @Test
    fun `console output shows FAILED header when any metric fails`() {
        val results = mapOf(
            CoverageMetric.LINE to MetricResult(161, 163, 98.8, 100, false),
            CoverageMetric.BRANCH to MetricResult(110, 110, 100.0, 100, true)
        )
        val output = buildConsoleOutput(":my-module", results, emptyList(), passed = false)
        assertTrue(output.contains("Coverage gate FAILED for :my-module"))
        assertTrue(output.contains("[FAIL]"))
    }

    @Test
    fun `console output includes min threshold for failing metrics`() {
        val results = mapOf(
            CoverageMetric.LINE to MetricResult(161, 163, 98.8, 100, false)
        )
        val output = buildConsoleOutput(":m", results, emptyList(), passed = false)
        assertTrue(output.contains("[min: 100%]"))
    }

    @Test
    fun `console output does not include min threshold for passing metrics`() {
        val results = mapOf(
            CoverageMetric.LINE to MetricResult(163, 163, 100.0, 100, true)
        )
        val output = buildConsoleOutput(":m", results, emptyList(), passed = true)
        assertFalse(output.contains("[min:"))
    }

    @Test
    fun `console output lists files with gaps`() {
        val results = mapOf(
            CoverageMetric.LINE to MetricResult(161, 163, 98.8, 100, false)
        )
        val files = listOf(
            FileCoverage("Foo.kt", "com/example", listOf(45, 47), listOf(45), missedBranches = 1, totalBranches = 2),
            FileCoverage("Bar.kt", "com/example", listOf(112), emptyList())
        )
        val output = buildConsoleOutput(":m", results, files, passed = false)
        assertTrue(output.contains("Files with gaps:"))
        assertTrue(output.contains("Foo.kt"))
        assertTrue(output.contains("Lines 45, 47 uncovered"))
        assertTrue(output.contains("Branches 45 missed (1 of 2)"))
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
        // Simulate plugin excluding BRANCH from metricResults before calling buildConsoleOutput
        val results = mapOf(
            CoverageMetric.LINE to MetricResult(100, 100, 100.0, 100, true)
            // BRANCH absent — as it would be if disabled
        )
        val output = buildConsoleOutput(":m", results, emptyList(), passed = true)
        assertTrue(output.contains("Line:"))
        assertFalse(output.contains("Branch:"), "Disabled metric must not appear in console output")
    }

    // --- buildJsonOutput ---

    @Test
    fun `JSON output contains module path`() {
        val json = buildJsonOutput(":my-module", true, emptyMap(), emptyList())
        assertTrue(json.contains("\"module\": \":my-module\""))
    }

    @Test
    fun `JSON passed field is true when all pass`() {
        val json = buildJsonOutput(":m", true, emptyMap(), emptyList())
        assertTrue(json.contains("\"passed\": true"))
    }

    @Test
    fun `JSON passed field is false when any fail`() {
        val json = buildJsonOutput(":m", false, emptyMap(), emptyList())
        assertTrue(json.contains("\"passed\": false"))
    }

    @Test
    fun `JSON metrics block contains correct fields`() {
        val results = mapOf(
            CoverageMetric.LINE to MetricResult(covered = 161, total = 163, percent = 98.8, threshold = 100, passed = false)
        )
        val json = buildJsonOutput(":m", false, results, emptyList())
        assertTrue(json.contains("\"LINE\""))
        assertTrue(json.contains("\"covered\": 161"))
        assertTrue(json.contains("\"total\": 163"))
        assertTrue(json.contains("\"percent\": 98.8"))
        assertTrue(json.contains("\"threshold\": 100"))
        assertTrue(json.contains("\"passed\": false"))
    }

    @Test
    fun `JSON files array contains gap files`() {
        val files = listOf(
            FileCoverage("Foo.kt", "com/example", listOf(45, 47), listOf(45))
        )
        val json = buildJsonOutput(":m", false, emptyMap(), files)
        assertTrue(json.contains("\"name\": \"Foo.kt\""))
        assertTrue(json.contains("\"package\": \"com/example\""))
        assertTrue(json.contains("\"uncoveredLines\": [45, 47]"))
        assertTrue(json.contains("\"missedBranchLines\": [45]"))
    }

    @Test
    fun `JSON files array is empty when no gaps`() {
        val json = buildJsonOutput(":m", true, emptyMap(), emptyList())
        assertTrue(json.contains("\"files\": []"))
    }

    @Test
    fun `disabled metric is absent from JSON metrics block`() {
        // Simulate plugin excluding BRANCH from metricResults before calling buildJsonOutput
        val results = mapOf(
            CoverageMetric.LINE to MetricResult(covered = 100, total = 100, percent = 100.0, threshold = 100, passed = true)
            // BRANCH absent — as it would be if disabled
        )
        val json = buildJsonOutput(":m", true, results, emptyList())
        assertTrue(json.contains("\"LINE\""), "Enabled metric must appear in JSON")
        assertFalse(json.contains("\"BRANCH\""), "Disabled metric must not appear in JSON")
    }
}
