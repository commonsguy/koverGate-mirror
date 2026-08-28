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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")
class DeltaFunctionalTest {

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
        rootProject.name = "delta-test"
        """
          .trimIndent()
      )
  }

  private fun writeBuildFile() {
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            id("org.jetbrains.kotlinx.kover") version "0.9.8"
            id("com.commonsware.kovergate")
        }
        repositories { mavenCentral() }
        dependencies { testImplementation(kotlin("test")) }
        tasks.test { useJUnitPlatform() }
        """
          .trimIndent()
      )
  }

  private fun writePartiallyCoveredSource() {
    val srcDir = File(projectDir, "src/main/kotlin/com/example")

    srcDir.mkdirs()
    File(srcDir, "Calculator.kt")
      .writeText(
        """
        package com.example
        class Calculator {
            fun add(a: Int, b: Int): Int = a + b
            fun subtract(a: Int, b: Int): Int = a - b
        }
        """
          .trimIndent()
      )

    val testDir = File(projectDir, "src/test/kotlin/com/example")

    testDir.mkdirs()
    File(testDir, "CalculatorTest.kt")
      .writeText(
        """
        package com.example
        import kotlin.test.Test
        import kotlin.test.assertEquals
        class CalculatorTest {
            private val calc = Calculator()
            @Test fun add() = assertEquals(5, calc.add(2, 3))
        }
        """
          .trimIndent()
      )
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  private fun gapReportFile() = File(projectDir, "build/reports/kover/gap-report.json")

  private fun baselineReportFile() = File(projectDir, "build/reports/kover/gap-report-baseline.json")

  private fun deltaReportFile() = File(projectDir, "build/reports/kover/gap-delta.json")

  private fun writeUncoveredMultiplyMethod() {
    File(projectDir, "src/main/kotlin/com/example/Calculator.kt")
      .writeText(
        """
        package com.example
        class Calculator {
            fun add(a: Int, b: Int): Int = a + b
            fun subtract(a: Int, b: Int): Int = a - b
            fun multiply(a: Int, b: Int): Int = a * b
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun `first build from clean leaves no baseline file`() {
    writeSettingsFile()
    writeBuildFile()
    writePartiallyCoveredSource()

    runner("koverGateGenerateReport").build()

    assertFalse(baselineReportFile().exists(), "No prior gap-report.json existed, so no baseline should be written")
  }

  @Test
  fun `second successive build rotates the first build's gap-report to the baseline`() {
    writeSettingsFile()
    writeBuildFile()
    writePartiallyCoveredSource()

    runner("koverGateGenerateReport").build()

    val firstReportContent = gapReportFile().readText()

    runner("koverGateGenerateReport").build()

    assertTrue(
      baselineReportFile().exists(),
      "Second build should have rotated the first build's report to the baseline",
    )
    assertEquals(firstReportContent, baselineReportFile().readText())
  }

  @Test
  fun `first build reports an absent baseline`() {
    writeSettingsFile()
    writeBuildFile()
    writePartiallyCoveredSource()

    runner("koverGateDelta").build()

    val deltaContent = deltaReportFile().readText()

    assertTrue(
      deltaContent.contains("\"baseline\":\"absent\""),
      "First build has no baseline to compare against. Output:\n$deltaContent",
    )
  }

  @Test
  fun `second build after a coverage-reducing edit reports a worsened file`() {
    writeSettingsFile()
    writeBuildFile()
    writePartiallyCoveredSource()

    runner("koverGateDelta").build()
    writeUncoveredMultiplyMethod()

    val second = runner("koverGateDelta").build()
    val deltaContent = deltaReportFile().readText()

    assertTrue(
      deltaContent.contains("\"baseline\":\"present\""),
      "Second build has a baseline from the first. Output:\n$deltaContent",
    )
    assertTrue(
      deltaContent.contains("\"worsened\"") && deltaContent.contains("Calculator.kt"),
      "New uncovered method should worsen Calculator.kt's delta entry. Output:\n$deltaContent",
    )
    assertTrue(second.output.contains(":koverGateDelta"))
  }

  @Test
  fun `a malformed baseline file is tolerated as absent without failing the build`() {
    writeSettingsFile()
    writeBuildFile()
    writePartiallyCoveredSource()

    runner("koverGateGenerateReport").build()
    baselineReportFile().parentFile.mkdirs()
    baselineReportFile().writeText("{ not valid json")

    // Excludes koverGateGenerateReport, whose own rotation would otherwise overwrite the malformed baseline with a
    // fresh, valid copy before koverGateDelta reads it — the scenario under test is a leftover corrupt file, not a
    // freshly-rotated one.
    runner("koverGateDelta", "-x", "koverGateGenerateReport").build()

    val deltaContent = deltaReportFile().readText()

    assertTrue(
      deltaContent.contains("\"baseline\":\"absent\""),
      "A malformed baseline must be treated as absent, not fail the build. Output:\n$deltaContent",
    )
  }
}
