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
class BumpFunctionalTest {

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
        rootProject.name = "bump-test"
        """
          .trimIndent()
      )
  }

  private fun writeBuildFile(threshold: Int) {
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            id("com.commonsware.kovergate")
        }
        repositories { mavenCentral() }
        koverGate {
            minLineCoverage.set($threshold)
            minBranchCoverage.set($threshold)
            minInstructionCoverage.set($threshold)
            minMethodCoverage.set($threshold)
            minClassCoverage.set($threshold)
            xmlReportFile.set(layout.projectDirectory.file("external/jacoco.xml"))
        }
        """
          .trimIndent()
      )
  }

  private fun writeCoverageXml(missed: Int) {
    val xmlFile = File(projectDir, "external/jacoco.xml")

    xmlFile.parentFile.mkdirs()
    xmlFile.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <report name="merged">
        <package name="com/example">
          <sourcefile name="Calculator.kt">
            <line nr="3" mi="$missed" ci="3" mb="0" cb="0"/>
          </sourcefile>
        </package>
        <counter type="LINE" missed="$missed" covered="1"/>
        <counter type="BRANCH" missed="$missed" covered="1"/>
        <counter type="METHOD" missed="$missed" covered="1"/>
        <counter type="CLASS" missed="$missed" covered="1"/>
        <counter type="INSTRUCTION" missed="$missed" covered="3"/>
      </report>
      """
        .trimIndent()
    )
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  @Test
  fun `bump raises thresholds from measured coverage`() {
    writeSettingsFile()
    writeBuildFile(threshold = 50)
    writeCoverageXml(missed = 0)

    val result = runner("koverGateBump").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateBump")?.outcome)
    assertTrue(result.output.contains("# koverGate: suggested threshold updates for :"))
    assertTrue(result.output.contains("minLineCoverage.set(100)"))
    assertTrue(result.output.contains("// was 50"))
  }

  @Test
  fun `bump applies the margin option`() {
    writeSettingsFile()
    writeBuildFile(threshold = 50)
    writeCoverageXml(missed = 0)

    val result = runner("koverGateBump", "--margin=10").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateBump")?.outcome)
    assertTrue(result.output.contains("minLineCoverage.set(90)"))
  }

  @Test
  fun `bump withholds reductions without allow-lower`() {
    writeSettingsFile()
    writeBuildFile(threshold = 100)
    writeCoverageXml(missed = 1)

    val result = runner("koverGateBump").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateBump")?.outcome)
    assertTrue(result.output.contains("# No threshold changes suggested."))
    assertTrue(result.output.contains("--allow-lower"))
  }

  @Test
  fun `bump emits reductions with allow-lower`() {
    writeSettingsFile()
    writeBuildFile(threshold = 100)
    writeCoverageXml(missed = 1)

    val result = runner("koverGateBump", "--allow-lower").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateBump")?.outcome)
    assertTrue(result.output.contains("minLineCoverage.set(50)"))
    assertTrue(result.output.contains("// was 100"))
  }
}
