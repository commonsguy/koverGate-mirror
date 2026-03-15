package com.commonsware.kovergate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoverageMetricTest {
  @Test
  fun `has exactly five metrics`() {
    assertEquals(5, CoverageMetric.entries.size)
  }

  @Test
  fun `contains expected metric names`() {
    val names = CoverageMetric.entries.map { it.name }.toSet()
    assertEquals(setOf("LINE", "BRANCH", "INSTRUCTION", "METHOD", "CLASS"), names)
  }
}
