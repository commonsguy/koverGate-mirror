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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("FunctionNaming", "MagicNumber")
class FreshnessReuseFunctionalTest {

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
        rootProject.name = "freshness-reuse-test"
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

  @Test
  fun `first failing run does not emit a producer-reuse warning`() {
    writeSettingsFile()
    writeBuildFile()
    writePartiallyCoveredSource()

    val result = runner("koverGateReport").buildAndFail()

    assertFalse(
      result.output.contains("KoverGate freshness:"),
      "First run executes the Kover XML task, so no reuse warning is expected. Output:\n${result.output}",
    )
  }

  @Test
  fun `second failing run warns that the Kover XML was reused`() {
    writeSettingsFile()
    writeBuildFile()
    writePartiallyCoveredSource()

    runner("koverGateReport").buildAndFail()

    val second = runner("koverGateReport").buildAndFail()

    assertTrue(
      second.output.contains("KoverGate freshness:"),
      "Second run reuses the Kover XML while the gate re-runs, so a reuse warning is expected. " +
        "Output:\n${second.output}",
    )
    assertTrue(second.output.contains("--rerun-tasks"))
  }
}
