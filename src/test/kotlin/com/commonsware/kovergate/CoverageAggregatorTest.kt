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

class CoverageAggregatorTest {

  private val widget =
    ClassCoverage(
      fqcn = "com.example.merged.Widget",
      sourcefilename = "Widget.kt",
      packageName = "com/example/merged",
      counters = mapOf(CoverageMetric.LINE to Counter(missed = 0, covered = 3)),
    )

  private val generated =
    ClassCoverage(
      fqcn = "com.example.merged.generated.Parser",
      sourcefilename = "Parser.kt",
      packageName = "com/example/merged/generated",
      counters = mapOf(CoverageMetric.LINE to Counter(missed = 10, covered = 0)),
    )

  private val widgetFile =
    FileCoverage(name = "Widget.kt", packageName = "com/example/merged", uncoveredLines = emptyList())

  private val parserFile =
    FileCoverage(name = "Parser.kt", packageName = "com/example/merged/generated", uncoveredLines = listOf(1, 2))

  private val raw =
    ParseResult(
      moduleCounters = mapOf(CoverageMetric.LINE to Counter(missed = 10, covered = 3)),
      files = listOf(widgetFile, parserFile),
      classes = listOf(widget, generated),
    )

  @Test
  fun `empty matcher reproduces raw moduleCounters and files unchanged`() {
    val matcher = ClassNameExclusionMatcher(emptySet())

    val result = CoverageAggregator.aggregate(raw, matcher)

    assertEquals(raw.moduleCounters, result.moduleCounters)
    assertEquals(raw.files, result.files)
    assertEquals(raw.classes, result.classes)
  }

  @Test
  fun `excluding a class removes it from recomputed counters and drops its lone sourcefile`() {
    val matcher = ClassNameExclusionMatcher(setOf("com.example.merged.generated.*"))

    val result = CoverageAggregator.aggregate(raw, matcher)

    assertEquals(Counter(missed = 0, covered = 3), result.moduleCounters[CoverageMetric.LINE])
    assertEquals(listOf(widgetFile), result.files)
    assertEquals(listOf(widget), result.classes)
  }

  @Test
  fun `sourcefile survives when at least one owning class is not excluded`() {
    val secondClassInSameFile =
      ClassCoverage(
        fqcn = "com.example.merged.generated.Parser2",
        sourcefilename = "Parser.kt",
        packageName = "com/example/merged/generated",
        counters = mapOf(CoverageMetric.LINE to Counter(missed = 0, covered = 4)),
      )
    val rawWithSharedFile = raw.copy(classes = raw.classes + secondClassInSameFile)
    val matcher = ClassNameExclusionMatcher(setOf("com.example.merged.generated.Parser"))

    val result = CoverageAggregator.aggregate(rawWithSharedFile, matcher)

    assertTrue(result.files.contains(parserFile))
    assertEquals(listOf(widget, secondClassInSameFile), result.classes)
  }

  @Test
  fun `file with no matching class is kept untouched`() {
    val orphanFile = FileCoverage(name = "Orphan.kt", packageName = "com/example/merged", uncoveredLines = emptyList())
    val rawWithOrphan = raw.copy(files = raw.files + orphanFile)
    val matcher = ClassNameExclusionMatcher(setOf("com.example.merged.generated.*"))

    val result = CoverageAggregator.aggregate(rawWithOrphan, matcher)

    assertTrue(result.files.contains(orphanFile))
  }
}
