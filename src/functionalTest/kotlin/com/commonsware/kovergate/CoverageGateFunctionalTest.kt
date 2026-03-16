package com.commonsware.kovergate

import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("FunctionNaming", "TooManyFunctions")
class CoverageGateFunctionalTest {

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
        rootProject.name = "test-project"
        """
          .trimIndent()
      )
  }

  private fun writeBuildFile(koverGateBlock: String = "") {
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
            plugins {
                kotlin("jvm") version "2.0.21"
                id("org.jetbrains.kotlinx.kover") version "0.9.1"
                id("com.commonsware.kovergate")
            }
            repositories { mavenCentral() }
            dependencies { testImplementation(kotlin("test")) }
            tasks.test { useJUnitPlatform() }
            $koverGateBlock
        """
          .trimIndent()
      )
  }

  private fun writeSourceFile(covered: Boolean) {
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
    if (covered) {
      // Tests that cover both methods
      File(testDir, "CalculatorTest.kt")
        .writeText(
          """
          package com.example
          import kotlin.test.Test
          import kotlin.test.assertEquals
          class CalculatorTest {
              private val calc = Calculator()
              @Test fun add() = assertEquals(5, calc.add(2, 3))
              @Test fun subtract() = assertEquals(1, calc.subtract(3, 2))
          }
          """
            .trimIndent()
        )
    } else {
      // Only covers add(), leaving subtract() uncovered
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
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  @Test
  fun `full coverage passes the gate`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = true)

    val result = runner("koverGapReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGapReport")?.outcome)
    assertTrue(result.output.contains("Coverage gate PASSED"))

    val json = File(projectDir, "build/reports/kover/gap-report.json").readText()
    val report = Json.decodeFromString<GapReport>(json)

    assertTrue(report.passed)
    assertTrue(report.files.isEmpty())
  }

  @Test
  fun `partial coverage fails the gate with correct gap report`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = false)

    val result = runner("koverGapReport").buildAndFail()

    assertEquals(TaskOutcome.FAILED, result.task(":koverGapReport")?.outcome)
    assertTrue(result.output.contains("Coverage gate FAILED"))

    val jsonFile = File(projectDir, "build/reports/kover/gap-report.json")

    assertTrue(jsonFile.exists(), "JSON report should be written even on failure")

    val json = jsonFile.readText()
    val report = Json.decodeFromString<GapReport>(json)

    assertFalse(report.passed)
    // Calculator.kt should appear in files with gaps
    assertTrue(
      report.files.any { it.name == "Calculator.kt" },
      "Expected Calculator.kt in gap files but got: ${report.files.map { it.name }}",
    )
  }

  @Test
  fun `custom thresholds are respected`() {
    writeSettingsFile()
    // Lower thresholds so partial coverage passes
    writeBuildFile(
      """
      koverGate {
          minLineCoverage.set(50)
          minBranchCoverage.set(50)
          minInstructionCoverage.set(50)
          minMethodCoverage.set(50)
          minClassCoverage.set(50)
      }
      """
        .trimIndent()
    )
    writeSourceFile(covered = false) // 50% method coverage

    val result = runner("koverGapReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGapReport")?.outcome)
  }

  @Test
  fun `disabled metrics are excluded from JSON output`() {
    writeSettingsFile()
    writeBuildFile(
      """
      koverGate {
          minLineCoverage.set(50)
          minBranchCoverage.set(50)
          minInstructionCoverage.set(50)
          minMethodCoverage.set(50)
          minClassCoverage.set(50)
          disabledMetrics.set(setOf(com.commonsware.kovergate.CoverageMetric.BRANCH))
      }
      """
        .trimIndent()
    )
    writeSourceFile(covered = true)

    runner("koverGapReport").build()

    val json = File(projectDir, "build/reports/kover/gap-report.json").readText()
    val report = Json.decodeFromString<GapReport>(json)

    assertNull(report.metrics["BRANCH"], "Disabled metrics must not appear in JSON")
  }

  @Test
  fun `JSON is written to correct location`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = true)

    runner("koverGapReport").build()

    val expected = File(projectDir, "build/reports/kover/gap-report.json")

    assertTrue(expected.exists())
  }

  @Test
  fun `task is UP-TO-DATE on second run when nothing changes`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = true)

    runner("koverGapReport").build()

    val result = runner("koverGapReport").build()

    assertEquals(TaskOutcome.UP_TO_DATE, result.task(":koverGapReport")?.outcome)
  }

  @Test
  fun `task re-runs when source file changes affect coverage`() {
    writeSettingsFile()
    // Use lowered thresholds so the gate passes even with one uncovered method
    writeBuildFile(
      """
      koverGate {
          minLineCoverage.set(50)
          minBranchCoverage.set(50)
          minInstructionCoverage.set(50)
          minMethodCoverage.set(50)
          minClassCoverage.set(50)
      }
      """
        .trimIndent()
    )
    writeSourceFile(covered = true)

    // First run — establishes UP-TO-DATE baseline
    runner("koverGapReport").build()

    // Add a new uncovered method — this changes bytecode, forces tests to
    // re-run,
    // produces a new Kover XML (new uncovered line), which changes
    // koverGapReport's @InputFile
    val src = File(projectDir, "src/main/kotlin/com/example/Calculator.kt")

    src.writeText(
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

    // Second run without --rerun-tasks: task must re-run because @InputFile
    // changed
    val result = runner("koverGapReport").build()

    assertNotEquals(TaskOutcome.UP_TO_DATE, result.task(":koverGapReport")?.outcome)
  }

  @Test
  fun `build fails with clear error when Kover plugin is not applied`() {
    writeSettingsFile()
    // Build file without Kover
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            id("com.commonsware.kovergate")
        }
        repositories { mavenCentral() }
        """
          .trimIndent()
      )

    val result = runner("tasks").buildAndFail()

    assertTrue(
      result.output.contains("KoverGate requires the Kover plugin"),
      "Error message should mention the missing Kover plugin. Got: ${result.output}",
    )
  }

  @Test
  fun `prettyPrintJson=true produces indented JSON output`() {
    writeSettingsFile()
    writeBuildFile(
      """
      koverGate {
          prettyPrintJson.set(true)
      }
      """
        .trimIndent()
    )
    writeSourceFile(covered = true)

    val result = runner("koverGapReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGapReport")?.outcome)

    val json = File(projectDir, "build/reports/kover/gap-report.json").readText()
    // Pretty output contains embedded newlines (not just a trailing newline)
    assertTrue(
      json.indexOf('\n') < json.trimEnd().lastIndex,
      "Pretty JSON should contain embedded newlines, but got: $json",
    )
    // Data must still be correct
    val report = Json.decodeFromString<GapReport>(json)

    assertTrue(report.passed)
  }
}
