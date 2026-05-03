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

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoverGateAggregateReportTaskTest {

  private val passingModule =
    GapReport(
      module = ":core",
      passed = true,
      metrics = mapOf("LINE" to MetricJson(100, 100, 100.0, 100, true)),
      files = emptyList(),
    )

  private val failingModule =
    GapReport(
      module = ":app",
      passed = false,
      metrics = mapOf("LINE" to MetricJson(80, 100, 80.0, 100, false)),
      files = listOf(FileGap("Foo.kt", "com.example", listOf(42), emptyList())),
    )

  @Test
  fun `passed is true when all modules pass`() {
    val json = buildAggregateJsonOutput(listOf(passingModule))
    val report = Json.decodeFromString<AggregateGapReport>(json)

    assertTrue(report.passed)
    assertEquals(1, report.modules.size)
  }

  @Test
  fun `passed is false when any module fails`() {
    val json = buildAggregateJsonOutput(listOf(passingModule, failingModule))
    val report = Json.decodeFromString<AggregateGapReport>(json)

    assertFalse(report.passed)
    assertEquals(2, report.modules.size)
  }

  @Test
  fun `empty module list produces passed=true aggregate`() {
    val json = buildAggregateJsonOutput(emptyList())
    val report = Json.decodeFromString<AggregateGapReport>(json)

    assertTrue(report.passed)
    assertTrue(report.modules.isEmpty())
  }

  @Test
  fun `prettyPrint=true produces indented JSON`() {
    val json = buildAggregateJsonOutput(listOf(passingModule), prettyPrint = true)

    assertTrue(
      json.indexOf('\n') < json.trimEnd().lastIndex,
      "Pretty JSON should contain embedded newlines",
    )
  }

  @Test
  fun `prettyPrint=false produces compact JSON`() {
    val json = buildAggregateJsonOutput(listOf(passingModule), prettyPrint = false)

    assertFalse(json.contains("\n"), "Compact JSON must not contain newlines")
  }

  @Test
  fun `module data is preserved in aggregate`() {
    val json = buildAggregateJsonOutput(listOf(passingModule, failingModule))
    val report = Json.decodeFromString<AggregateGapReport>(json)

    assertEquals(":core", report.modules[0].module)
    assertEquals(":app", report.modules[1].module)
    assertEquals(1, report.modules[1].files.size)
    assertEquals("Foo.kt", report.modules[1].files[0].name)
  }
}
