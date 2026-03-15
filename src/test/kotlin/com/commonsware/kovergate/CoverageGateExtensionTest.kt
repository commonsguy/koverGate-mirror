package com.commonsware.kovergate

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverageGateExtensionTest {
  private val project = ProjectBuilder.builder().build()
  private val ext = project.objects.newInstance(CoverageGateExtension::class.java)

  @Test
  fun `all thresholds default to 100`() {
    assertEquals(100, ext.minLineCoverage.get())
    assertEquals(100, ext.minBranchCoverage.get())
    assertEquals(100, ext.minInstructionCoverage.get())
    assertEquals(100, ext.minMethodCoverage.get())
    assertEquals(100, ext.minClassCoverage.get())
  }

  @Test
  fun `disabledMetrics defaults to empty set`() {
    assertTrue(ext.disabledMetrics.get().isEmpty())
  }

  @Test
  fun `thresholds can be changed`() {
    ext.minLineCoverage.set(80)
    ext.minBranchCoverage.set(70)
    assertEquals(80, ext.minLineCoverage.get())
    assertEquals(70, ext.minBranchCoverage.get())
  }

  @Test
  fun `metrics can be disabled`() {
    ext.disabledMetrics.set(setOf(CoverageMetric.INSTRUCTION, CoverageMetric.METHOD))
    assertEquals(setOf(CoverageMetric.INSTRUCTION, CoverageMetric.METHOD), ext.disabledMetrics.get())
  }

  @Test
  fun `prettyPrintJson defaults to false`() {
    assertFalse(ext.prettyPrintJson.get())
  }
}
