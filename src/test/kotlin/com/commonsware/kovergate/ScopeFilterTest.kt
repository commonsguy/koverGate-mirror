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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("FunctionNaming")
class ScopeFilterTest {

  private fun report(vararg files: FileGap) =
    GapReport(module = ":app", passed = false, metrics = emptyMap(), files = files.toList())

  @Test
  fun `keeps only uncovered lines that were changed`() {
    val gap =
      FileGap("Calculator.kt", "com/example", uncoveredLines = listOf(5, 6, 20), missedBranchLines = emptyList())
    val filter =
      BranchDiffScopeFilter(
        changedLinesByPath = mapOf("src/main/kotlin/com/example/Calculator.kt" to setOf(5, 6, 7)),
        resolvePath = { "src/main/kotlin/com/example/Calculator.kt" },
      )

    val result = filter.apply(report(gap))

    assertEquals(1, result.files.size)
    assertEquals(listOf(5, 6), result.files[0].uncoveredLines)
  }

  @Test
  fun `filters missed branch lines by changed lines`() {
    val gap =
      FileGap(
        "Foo.kt",
        "com/example",
        uncoveredLines = emptyList(),
        missedBranchLines = listOf(BranchGap(10, 1, 2), BranchGap(11, 1, 2)),
      )
    val filter =
      BranchDiffScopeFilter(changedLinesByPath = mapOf("p/Foo.kt" to setOf(11)), resolvePath = { "p/Foo.kt" })

    val result = filter.apply(report(gap))

    assertEquals(listOf(BranchGap(11, 1, 2)), result.files[0].missedBranchLines)
  }

  @Test
  fun `drops files with no changed gap lines`() {
    val gap = FileGap("Bar.kt", "com/example", uncoveredLines = listOf(100), missedBranchLines = emptyList())
    val filter =
      BranchDiffScopeFilter(changedLinesByPath = mapOf("p/Bar.kt" to setOf(1, 2)), resolvePath = { "p/Bar.kt" })

    val result = filter.apply(report(gap))

    assertTrue(result.files.isEmpty())
  }

  @Test
  fun `drops files that cannot be resolved to a path`() {
    val gap = FileGap("Ghost.kt", "com/example", uncoveredLines = listOf(1), missedBranchLines = emptyList())
    val filter = BranchDiffScopeFilter(changedLinesByPath = mapOf("p/Ghost.kt" to setOf(1)), resolvePath = { null })

    val result = filter.apply(report(gap))

    assertTrue(result.files.isEmpty())
  }

  @Test
  fun `passed is true when no in-scope gaps remain`() {
    val gap = FileGap("Bar.kt", "com/example", uncoveredLines = listOf(100), missedBranchLines = emptyList())
    val filter = BranchDiffScopeFilter(changedLinesByPath = emptyMap(), resolvePath = { "p/Bar.kt" })

    val result = filter.apply(report(gap))

    assertTrue(result.passed)
    assertTrue(result.metrics.isEmpty())
  }

  @Test
  fun `passed is false when in-scope gaps remain and metrics are dropped`() {
    val gap = FileGap("Bar.kt", "com/example", uncoveredLines = listOf(5), missedBranchLines = emptyList())
    val filter = BranchDiffScopeFilter(changedLinesByPath = mapOf("p/Bar.kt" to setOf(5)), resolvePath = { "p/Bar.kt" })

    val result = filter.apply(report(gap))

    assertFalse(result.passed)
    assertTrue(result.metrics.isEmpty())
    assertEquals(":app", result.module)
  }
}
