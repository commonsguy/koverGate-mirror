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

import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("FunctionNaming", "MagicNumber")
class ProvenanceFunctionalTest {

  @TempDir lateinit var projectDir: File

  private val oneDayMillis = 86_400_000L

  private fun writeSettingsFile() {
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
        }
        rootProject.name = "provenance-test"
        """
          .trimIndent()
      )
  }

  private fun writeExternalXmlBuildFile() {
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            id("com.commonsware.kovergate")
        }
        repositories { mavenCentral() }
        koverGate {
            minLineCoverage.set(50)
            minBranchCoverage.set(50)
            minInstructionCoverage.set(50)
            minMethodCoverage.set(50)
            minClassCoverage.set(50)
            xmlReportFile.set(layout.projectDirectory.file("external/jacoco.xml"))
        }
        """
          .trimIndent()
      )
  }

  private fun writeJacocoXml(): File {
    val xmlFile = File(projectDir, "external/jacoco.xml")

    xmlFile.parentFile.mkdirs()
    xmlFile.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <report name="merged">
        <package name="com/example">
          <sourcefile name="Calculator.kt">
            <line nr="3" mi="0" ci="3" mb="0" cb="0"/>
            <line nr="4" mi="0" ci="3" mb="0" cb="0"/>
            <counter type="LINE" missed="0" covered="2"/>
            <counter type="METHOD" missed="0" covered="2"/>
            <counter type="CLASS" missed="0" covered="1"/>
            <counter type="INSTRUCTION" missed="0" covered="6"/>
          </sourcefile>
        </package>
        <counter type="LINE" missed="0" covered="2"/>
        <counter type="METHOD" missed="0" covered="2"/>
        <counter type="CLASS" missed="0" covered="1"/>
        <counter type="INSTRUCTION" missed="0" covered="6"/>
      </report>
      """
        .trimIndent()
    )

    return xmlFile
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  private fun readReport(): GapReport =
    Json.decodeFromString(File(projectDir, "build/reports/kover/gap-report.json").readText())

  @Test
  fun `gap report includes a source provenance block`() {
    writeSettingsFile()
    writeExternalXmlBuildFile()
    writeJacocoXml()

    val result = runner("koverGateGenerateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateGenerateReport")?.outcome)

    val report = readReport()

    assertNotNull(report.source)
    assertTrue(report.source!!.xmlPath.endsWith("jacoco.xml"))
  }

  @Test
  fun `a stale Kover XML logs a freshness warning and marks the report stale`() {
    writeSettingsFile()
    writeExternalXmlBuildFile()
    val xmlFile = writeJacocoXml()

    xmlFile.setLastModified(System.currentTimeMillis() - oneDayMillis)

    val result = runner("koverGateGenerateReport").build()

    assertTrue(result.output.contains("STALE"), "Expected a STALE warning in output:\n${result.output}")
    assertTrue(result.output.contains("--rerun-tasks"))

    val report = readReport()

    assertTrue(report.source!!.stale)
  }

  @Test
  fun `a fresh Kover XML produces no freshness warning`() {
    writeSettingsFile()
    writeExternalXmlBuildFile()
    val xmlFile = writeJacocoXml()

    xmlFile.setLastModified(System.currentTimeMillis() + oneDayMillis)

    val result = runner("koverGateGenerateReport").build()

    assertFalse(result.output.contains("may be STALE"))

    val report = readReport()

    assertFalse(report.source!!.stale)
  }
}
