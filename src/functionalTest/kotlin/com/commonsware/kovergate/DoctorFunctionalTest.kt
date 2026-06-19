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
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("FunctionNaming", "MagicNumber")
class DoctorFunctionalTest {

  @TempDir lateinit var projectDir: File

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
        rootProject.name = "doctor-test"
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

  private fun writeJacocoXml() {
    val xmlFile = File(projectDir, "external/jacoco.xml")

    xmlFile.parentFile.mkdirs()
    xmlFile.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <report name="merged">
        <package name="com/example">
          <sourcefile name="Calculator.kt">
            <line nr="3" mi="0" ci="3" mb="0" cb="0"/>
            <counter type="LINE" missed="0" covered="1"/>
            <counter type="METHOD" missed="0" covered="1"/>
            <counter type="CLASS" missed="0" covered="1"/>
            <counter type="INSTRUCTION" missed="0" covered="3"/>
          </sourcefile>
        </package>
        <counter type="LINE" missed="0" covered="1"/>
        <counter type="METHOD" missed="0" covered="1"/>
        <counter type="CLASS" missed="0" covered="1"/>
        <counter type="INSTRUCTION" missed="0" covered="3"/>
      </report>
      """
        .trimIndent()
    )
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  @Test
  fun `doctor prints thresholds, XML path, freshness, and excludes`() {
    writeSettingsFile()
    writeExternalXmlBuildFile()
    writeJacocoXml()

    val result = runner("koverGateDoctor").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateDoctor")?.outcome)
    assertTrue(result.output.contains("KoverGate doctor for"))
    assertTrue(result.output.contains("LINE: 50% (enabled)"))
    assertTrue(result.output.contains("Kover XML:"))
    assertTrue(result.output.contains("stale: false"))
    assertTrue(result.output.contains("(none)"))
  }

  @Test
  fun `doctor reports a missing XML without failing`() {
    writeSettingsFile()
    writeExternalXmlBuildFile()

    val result = runner("koverGateDoctor").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateDoctor")?.outcome)
    assertTrue(result.output.contains("exists: false"))
  }
}
