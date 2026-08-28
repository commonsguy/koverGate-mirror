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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GapKindClassifierTest {
  @Test
  fun `gapKind is COLD when only uncovered lines are present`() {
    val file = FileCoverage("Foo.kt", "p", uncoveredLines = listOf(1), partiallyCoveredLines = emptyList())

    assertEquals(GapKind.COLD, classifyGapKind(file))
  }

  @Test
  fun `gapKind is RESIDUAL when only partially covered lines are present`() {
    val file = FileCoverage("Foo.kt", "p", uncoveredLines = emptyList(), partiallyCoveredLines = listOf(1))

    assertEquals(GapKind.RESIDUAL, classifyGapKind(file))
  }

  @Test
  fun `gapKind is MIXED when both uncovered and partially covered lines are present`() {
    val file = FileCoverage("Foo.kt", "p", uncoveredLines = listOf(1), partiallyCoveredLines = listOf(2))

    assertEquals(GapKind.MIXED, classifyGapKind(file))
  }

  @Test
  fun `gapKind is BRANCH_ARMS_ONLY when neither uncovered nor partially covered lines are present`() {
    val file = FileCoverage("Foo.kt", "p", uncoveredLines = emptyList(), partiallyCoveredLines = emptyList())

    assertEquals(GapKind.BRANCH_ARMS_ONLY, classifyGapKind(file))
  }

  @Test
  fun `dominantGapKind is COLD when cold instructions outnumber residual`() {
    val file = FileCoverage("Foo.kt", "p", listOf(1), missedInstructionsCold = 10, missedInstructionsResidual = 2)

    assertEquals(GapKind.COLD, classifyDominantGapKind(file))
  }

  @Test
  fun `dominantGapKind is RESIDUAL when residual instructions outnumber cold`() {
    val file = FileCoverage("Foo.kt", "p", listOf(1), missedInstructionsCold = 2, missedInstructionsResidual = 10)

    assertEquals(GapKind.RESIDUAL, classifyDominantGapKind(file))
  }

  @Test
  fun `dominantGapKind is null on an exact tie`() {
    val file = FileCoverage("Foo.kt", "p", listOf(1), missedInstructionsCold = 12, missedInstructionsResidual = 12)

    assertNull(classifyDominantGapKind(file))
  }

  @Test
  fun `dominantGapKind is null when both counts are zero`() {
    val file = FileCoverage("Foo.kt", "p", emptyList())

    assertNull(classifyDominantGapKind(file))
  }

  @Test
  fun `dominantGapKind never returns MIXED or BRANCH_ARMS_ONLY`() {
    val cases =
      listOf(
        FileCoverage("A.kt", "p", listOf(1), missedInstructionsCold = 10, missedInstructionsResidual = 2),
        FileCoverage("B.kt", "p", listOf(1), missedInstructionsCold = 2, missedInstructionsResidual = 10),
        FileCoverage("C.kt", "p", listOf(1), missedInstructionsCold = 12, missedInstructionsResidual = 12),
        FileCoverage("D.kt", "p", emptyList()),
      )

    cases.forEach { file ->
      val result = classifyDominantGapKind(file)

      assertTrue(result == null || result == GapKind.COLD || result == GapKind.RESIDUAL)
    }
  }
}
