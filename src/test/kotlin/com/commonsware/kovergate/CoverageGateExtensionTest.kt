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
  fun `attributeCoverageFrom records a contribution with its exclude packages`() {
    ext.attributeCoverageFrom(":consumer", excludePackages = listOf("com.consumer"))

    assertEquals(1, ext.coverageContributions.size)
    assertEquals(":consumer", ext.coverageContributions.single().modulePath)
    assertEquals(listOf("com.consumer"), ext.coverageContributions.single().excludePackages)
  }

  @Test
  fun `attributeCoverageFrom defaults excludePackages to empty and allows multiple contributors`() {
    ext.attributeCoverageFrom(":a")
    ext.attributeCoverageFrom(":b", excludePackages = listOf("com.b"))

    assertEquals(listOf(":a", ":b"), ext.coverageContributions.map { it.modulePath })
    assertTrue(ext.coverageContributions.first().excludePackages.isEmpty())
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

  @Test
  fun `xmlReportFile is absent by default`() {
    assertFalse(ext.xmlReportFile.isPresent)
  }
}
