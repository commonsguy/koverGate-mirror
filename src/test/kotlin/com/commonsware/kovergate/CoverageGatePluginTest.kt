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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverageGatePluginTest {

  @Test
  fun `plugin registers koverGate extension`() {
    val project = ProjectBuilder.builder().build()
    // Apply Kover first so afterEvaluate check passes
    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    project.pluginManager.apply("com.commonsware.kovergate")

    assertNotNull(project.extensions.findByName("koverGate"))
    assertNotNull(project.extensions.findByType(CoverageGateExtension::class.java))
  }

  @Test
  fun `plugin registers koverGateGenerateReport task`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    project.pluginManager.apply("com.commonsware.kovergate")

    val task = project.tasks.findByName("koverGateGenerateReport")

    assertNotNull(task)
    assertTrue(task is KoverGateReportTask)
  }

  @Test
  fun `plugin registers koverGateReport task`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    project.pluginManager.apply("com.commonsware.kovergate")

    val task = project.tasks.findByName("koverGateReport")

    assertNotNull(task)
    assertTrue(task is KoverGateCheckTask)
  }

  @Test
  fun `koverGateReport task is in verification group`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    project.pluginManager.apply("com.commonsware.kovergate")

    val task = project.tasks.getByName("koverGateReport")

    assertEquals("verification", task.group)
  }

  @Test
  fun `extension defaults are wired to generate task`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    project.pluginManager.apply("com.commonsware.kovergate")

    val task = project.tasks.getByName("koverGateGenerateReport") as KoverGateReportTask

    assertEquals(100, task.minLineCoverage.get())
    assertEquals(100, task.minBranchCoverage.get())
    assertTrue(task.disabledMetrics.get().isEmpty())
    assertFalse(task.prettyPrintJson.get())
  }

  @Test
  fun `prettyPrintJson DSL change propagates to generate task`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    project.pluginManager.apply("com.commonsware.kovergate")

    project.extensions.getByType(CoverageGateExtension::class.java).prettyPrintJson.set(true)

    val task = project.tasks.getByName("koverGateGenerateReport") as KoverGateReportTask

    assertTrue(task.prettyPrintJson.get())
  }

  @Test
  fun `DSL changes propagate to generate task`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    project.pluginManager.apply("com.commonsware.kovergate")

    project.extensions.getByType(CoverageGateExtension::class.java).apply {
      minLineCoverage.set(80)
      disabledMetrics.set(setOf(CoverageMetric.INSTRUCTION))
    }

    val task = project.tasks.getByName("koverGateGenerateReport") as KoverGateReportTask

    assertEquals(80, task.minLineCoverage.get())
    assertEquals(setOf(CoverageMetric.INSTRUCTION), task.disabledMetrics.get())
  }

  @Test
  fun `xmlReportTask defaults to empty string`() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    project.pluginManager.apply("com.commonsware.kovergate")

    val ext = project.extensions.getByType(CoverageGateExtension::class.java)
    assertEquals("", ext.xmlReportTask.get())
  }

  @Test
  fun `root-with-subprojects registers RootCoverageGateExtension not CoverageGateExtension`() {
    val root = ProjectBuilder.builder().withName("root").build()

    ProjectBuilder.builder().withParent(root).withName("child").build()
    root.pluginManager.apply("com.commonsware.kovergate")

    assertNotNull(root.extensions.findByType(RootCoverageGateExtension::class.java))
    assertNull(root.extensions.findByType(CoverageGateExtension::class.java))
  }

  @Test
  fun `root prettyPrintJson propagates to aggregate task`() {
    val root = ProjectBuilder.builder().withName("root").build()

    ProjectBuilder.builder().withParent(root).withName("child").build()
    root.pluginManager.apply("com.commonsware.kovergate")

    root.extensions.getByType(RootCoverageGateExtension::class.java).prettyPrintJson.set(true)

    val task = root.tasks.getByName("koverGateReport") as KoverGateAggregateReportTask

    assertTrue(task.prettyPrintJson.get())
  }

  @Test
  fun `not-found message names the task and project and omits the hint without candidates`() {
    val message = buildXmlReportTaskNotFoundMessage("koverXmlReportDebg", ":app", emptyList())

    assertEquals(
      "KoverGate: xmlReportTask 'koverXmlReportDebg' not found in project ':app'. " +
        "Ensure the task name is correct and the Kover plugin is applied.",
      message,
    )
  }

  @Test
  fun `not-found message lists available koverXmlReport tasks when there are candidates`() {
    val message =
      buildXmlReportTaskNotFoundMessage(
        "koverXmlReportDebg",
        ":app",
        listOf("koverXmlReport", "koverXmlReportDebug", "koverXmlReportRelease"),
      )

    assertTrue(message.startsWith("KoverGate: xmlReportTask 'koverXmlReportDebg' not found in project ':app'."))
    assertTrue(
      message.endsWith(" Available koverXmlReport* tasks: koverXmlReport, koverXmlReportDebug, koverXmlReportRelease.")
    )
  }

  @Test
  fun `qualifiedTaskPath prefixes a nested project path`() {
    assertEquals(":app:koverXmlReportDebug", qualifiedTaskPath(":app", "koverXmlReportDebug"))
  }

  @Test
  fun `qualifiedTaskPath does not double the colon for the root project`() {
    assertEquals(":koverXmlReportDebug", qualifiedTaskPath(":", "koverXmlReportDebug"))
  }
}
