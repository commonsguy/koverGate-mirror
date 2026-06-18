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
import java.lang.ProcessBuilder
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("FunctionNaming", "TooManyFunctions", "MagicNumber", "LargeClass")
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
                id("org.jetbrains.kotlinx.kover") version "0.9.8"
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

  private fun writeJacocoXmlFile(relativePath: String) {
    val xmlFile = File(projectDir, relativePath)

    xmlFile.parentFile.mkdirs()
    xmlFile.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <report name="merged">
        <package name="com/example">
          <sourcefile name="Calculator.kt">
            <line nr="3" mi="0" ci="3" mb="0" cb="0"/>
            <line nr="4" mi="3" ci="0" mb="0" cb="0"/>
            <counter type="LINE" missed="1" covered="1"/>
            <counter type="METHOD" missed="1" covered="1"/>
            <counter type="CLASS" missed="0" covered="1"/>
            <counter type="INSTRUCTION" missed="3" covered="3"/>
          </sourcefile>
        </package>
        <counter type="LINE" missed="1" covered="1"/>
        <counter type="METHOD" missed="1" covered="1"/>
        <counter type="CLASS" missed="0" covered="1"/>
        <counter type="INSTRUCTION" missed="3" covered="3"/>
      </report>
      """
        .trimIndent()
    )
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  private fun initGitRepo() {
    ProcessBuilder("git", "init").directory(projectDir).start().waitFor()
    ProcessBuilder("git", "config", "user.email", "test@example.com").directory(projectDir).start().waitFor()
    ProcessBuilder("git", "config", "user.name", "Test User").directory(projectDir).start().waitFor()
    ProcessBuilder("git", "add", ".").directory(projectDir).start().waitFor()
    ProcessBuilder("git", "commit", "-m", "initial").directory(projectDir).start().waitFor()
  }

  @Test
  fun `full coverage passes the gate`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = true)

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)
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

    val result = runner("koverGateReport").buildAndFail()

    assertEquals(TaskOutcome.FAILED, result.task(":koverGateReport")?.outcome)
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

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)
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

    runner("koverGateReport").build()

    val json = File(projectDir, "build/reports/kover/gap-report.json").readText()
    val report = Json.decodeFromString<GapReport>(json)

    assertNull(report.metrics["BRANCH"], "Disabled metrics must not appear in JSON")
  }

  @Test
  fun `JSON is written to correct location`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = true)

    runner("koverGateReport").build()

    val expected = File(projectDir, "build/reports/kover/gap-report.json")

    assertTrue(expected.exists())
  }

  @Test
  fun `task is UP-TO-DATE on second run when nothing changes`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = true)

    runner("koverGateReport").build()

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.UP_TO_DATE, result.task(":koverGateReport")?.outcome)
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
    runner("koverGateReport").build()

    // Add a new uncovered method — this changes bytecode, forces tests to
    // re-run,
    // produces a new Kover XML (new uncovered line), which changes
    // koverGateReport's @InputFile
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
    val result = runner("koverGateReport").build()

    assertNotEquals(TaskOutcome.UP_TO_DATE, result.task(":koverGateReport")?.outcome)
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

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)

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

  @Test
  fun `task is compatible with configuration cache`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = true)

    // First run: populates the configuration cache
    runner("koverGateReport", "--configuration-cache").build()

    // Second run: must reuse the configuration cache
    val result = runner("koverGateReport", "--configuration-cache").build()

    assertTrue(
      result.output.contains("Reusing configuration cache"),
      "Expected configuration cache to be reused on second run. Output:\n${result.output}",
    )
  }

  @Test
  fun `gate re-evaluates coverage when only test code changes under configuration cache`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = false)

    // First run: subtract() is uncovered, so the gate must fail and write a failing gap report.
    val first = runner("koverGateReport", "--configuration-cache").buildAndFail()

    assertTrue(first.output.contains("Coverage gate FAILED"), "Expected first run to fail. Output:\n${first.output}")

    // Change ONLY test code so subtract() is now covered; build config is untouched.
    writeSourceFile(covered = true)

    val second = runner("koverGateReport", "--configuration-cache").build()

    assertEquals(TaskOutcome.SUCCESS, second.task(":koverGateReport")?.outcome)

    val report = Json.decodeFromString<GapReport>(File(projectDir, "build/reports/kover/gap-report.json").readText())

    assertTrue(report.passed, "Expected the second run to re-evaluate to a passing report. Output:\n${second.output}")
    assertTrue(report.files.isEmpty(), "Expected no remaining gaps; got ${report.files.map { it.name }}")
  }

  @Test
  fun `attributeCoverageFrom counts a sibling module's tests and excludes its own classes`() {
    writeAttributionSettingsFile()
    writeAttributionProducerModule()
    writeAttributionConsumerModule()

    val result = runner(":producer:koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":producer:koverGateReport")?.outcome)

    val report =
      Json.decodeFromString<GapReport>(File(projectDir, "producer/build/reports/kover/gap-report.json").readText())

    assertTrue(report.passed, "Expected producer gate to pass via attributed coverage. Output:\n${result.output}")
    assertTrue(report.files.isEmpty(), "Expected no gaps after exclusion; got ${report.files.map { it.name }}")
  }

  @Test
  fun `explicit xmlReportTask override runs successfully`() {
    writeSettingsFile()
    writeBuildFile(
      """
      koverGate {
          xmlReportTask.set("koverXmlReportJvm")
      }
      """
        .trimIndent()
    )
    writeSourceFile(covered = true)

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)
    assertTrue(result.output.contains("Coverage gate PASSED"))
  }

  @Test
  fun `xmlReportFile override is consumed without Kover applied`() {
    writeSettingsFile()
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
    writeJacocoXmlFile("external/jacoco.xml")

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)

    val report = Json.decodeFromString<GapReport>(File(projectDir, "build/reports/kover/gap-report.json").readText())

    assertTrue(report.files.any { it.name == "Calculator.kt" && it.uncoveredLines.contains(4) })
  }

  @Test
  fun `xmlReportFile override does not raise the Kover-required error`() {
    writeSettingsFile()
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            id("com.commonsware.kovergate")
        }
        repositories { mavenCentral() }
        koverGate { xmlReportFile.set(layout.projectDirectory.file("external/jacoco.xml")) }
        """
          .trimIndent()
      )
    writeJacocoXmlFile("external/jacoco.xml")

    val result = runner("tasks").build()

    assertFalse(result.output.contains("KoverGate requires the Kover plugin"))
  }

  @Test
  fun `xmlReportFile wired from a producing task carries an implicit dependency`() {
    writeSettingsFile()
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            id("com.commonsware.kovergate")
        }
        repositories { mavenCentral() }
        abstract class MakeReport : DefaultTask() {
            @get:org.gradle.api.tasks.OutputFile abstract val out: org.gradle.api.file.RegularFileProperty
            @org.gradle.api.tasks.TaskAction fun run() {
                out.get().asFile.writeText(${'"'}""<?xml version="1.0"?><report name="m"><counter type="LINE" missed="0" covered="1"/><counter type="METHOD" missed="0" covered="1"/><counter type="CLASS" missed="0" covered="1"/><counter type="INSTRUCTION" missed="0" covered="1"/></report>""${'"'})
            }
        }
        val makeReport = tasks.register<MakeReport>("makeReport") {
            out.set(layout.buildDirectory.file("generated/jacoco.xml"))
        }
        koverGate { xmlReportFile.set(makeReport.flatMap { it.out }) }
        """
          .trimIndent()
      )

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":makeReport")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)
  }

  // ── Attribution test helpers ──────────────────────────────────────────────

  private fun writeAttributionSettingsFile() {
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
        }
        rootProject.name = "attribution-project"
        include(":producer", ":consumer")
        """
          .trimIndent()
      )
  }

  private fun writeAttributionProducerModule() {
    val producerDir = File(projectDir, "producer")

    File(producerDir, "build.gradle.kts")
      .apply { parentFile.mkdirs() }
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
        koverGate {
            attributeCoverageFrom(":consumer", excludePackages = listOf("com.consumer"))
        }
        """
          .trimIndent()
      )
    File(producerDir, "src/main/kotlin/com/producer/Widget.kt")
      .apply { parentFile.mkdirs() }
      .writeText(
        """
        package com.producer
        class Widget {
            fun common(): Int = 1
            fun special(): Int = 2
        }
        """
          .trimIndent()
      )
    File(producerDir, "src/test/kotlin/com/producer/WidgetTest.kt")
      .apply { parentFile.mkdirs() }
      .writeText(
        """
        package com.producer
        import kotlin.test.Test
        import kotlin.test.assertEquals
        class WidgetTest {
            @Test fun common() = assertEquals(1, Widget().common())
        }
        """
          .trimIndent()
      )
  }

  private fun writeAttributionConsumerModule() {
    val consumerDir = File(projectDir, "consumer")

    File(consumerDir, "build.gradle.kts")
      .apply { parentFile.mkdirs() }
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            id("org.jetbrains.kotlinx.kover") version "0.9.8"
        }
        repositories { mavenCentral() }
        dependencies {
            implementation(project(":producer"))
            testImplementation(kotlin("test"))
        }
        tasks.test { useJUnitPlatform() }
        """
          .trimIndent()
      )
    File(consumerDir, "src/main/kotlin/com/consumer/Helper.kt")
      .apply { parentFile.mkdirs() }
      .writeText(
        """
        package com.consumer
        class Helper {
            fun used(): Int = 10
            fun unused(): Int = 20
        }
        """
          .trimIndent()
      )
    File(consumerDir, "src/test/kotlin/com/consumer/ConformanceTest.kt")
      .apply { parentFile.mkdirs() }
      .writeText(
        """
        package com.consumer
        import com.producer.Widget
        import kotlin.test.Test
        import kotlin.test.assertEquals
        class ConformanceTest {
            @Test fun coversProducerSpecial() = assertEquals(2, Widget().special())
            @Test fun coversHelperUsed() = assertEquals(10, Helper().used())
        }
        """
          .trimIndent()
      )
  }

  // ── Multi-module helpers ──────────────────────────────────────────────────

  private fun writeMultiModuleSettingsFile() {
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
        }
        rootProject.name = "multi-module-test"
        include(":moduleA", ":moduleB")
        """
          .trimIndent()
      )
  }

  private fun writeRootBuildFile(koverGateBlock: String = "") {
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("com.commonsware.kovergate")
        }
        $koverGateBlock
        """
          .trimIndent()
      )
  }

  private fun writeSubmoduleBuildFile(moduleDir: File, koverGateBlock: String = "") {
    File(moduleDir, "build.gradle.kts")
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
        $koverGateBlock
        """
          .trimIndent()
      )
  }

  private fun writeSubmoduleSource(moduleDir: File, covered: Boolean) {
    val srcDir = File(moduleDir, "src/main/kotlin/com/example")

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

    val testDir = File(moduleDir, "src/test/kotlin/com/example")

    testDir.mkdirs()
    if (covered) {
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

  // ── Multi-module tests ────────────────────────────────────────────────────

  @Test
  fun `aggregate report is generated for fully covered multi-module project`() {
    writeMultiModuleSettingsFile()
    writeRootBuildFile()
    val moduleA = File(projectDir, "moduleA").also { it.mkdirs() }
    val moduleB = File(projectDir, "moduleB").also { it.mkdirs() }

    writeSubmoduleBuildFile(moduleA)
    writeSubmoduleBuildFile(moduleB)
    writeSubmoduleSource(moduleA, covered = true)
    writeSubmoduleSource(moduleB, covered = true)

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":moduleA:koverGateReport")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":moduleB:koverGateReport")?.outcome)

    val aggregateFile = File(projectDir, "build/reports/kover/gap-report.json")

    assertTrue(aggregateFile.exists(), "Aggregate gap report should be written at root")

    val aggregate = Json.decodeFromString<AggregateGapReport>(aggregateFile.readText())

    assertTrue(aggregate.passed)
    assertEquals(2, aggregate.modules.size)
    assertTrue(aggregate.modules.any { it.module == ":moduleA" })
    assertTrue(aggregate.modules.any { it.module == ":moduleB" })
  }

  @Test
  fun `aggregate report captures gap details from submodule with lowered thresholds`() {
    writeMultiModuleSettingsFile()
    writeRootBuildFile()
    val moduleA = File(projectDir, "moduleA").also { it.mkdirs() }
    val moduleB = File(projectDir, "moduleB").also { it.mkdirs() }

    writeSubmoduleBuildFile(moduleA)
    // Lower thresholds so partial coverage still passes the gate,
    // but gap files are still present in the JSON
    writeSubmoduleBuildFile(
      moduleB,
      """
      koverGate {
          minLineCoverage.set(50)
          minBranchCoverage.set(50)
          minInstructionCoverage.set(50)
          minMethodCoverage.set(50)
          minClassCoverage.set(50)
      }
      """
        .trimIndent(),
    )
    writeSubmoduleSource(moduleA, covered = true)
    writeSubmoduleSource(moduleB, covered = false)

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)

    val aggregateFile = File(projectDir, "build/reports/kover/gap-report.json")
    val aggregate = Json.decodeFromString<AggregateGapReport>(aggregateFile.readText())

    assertTrue(aggregate.passed)
    val moduleBReport = aggregate.modules.first { it.module == ":moduleB" }

    assertTrue(moduleBReport.files.isNotEmpty(), "moduleB should have gap files even though thresholds are lowered")
  }

  @Test
  fun `aggregate report is not generated when a submodule fails its coverage gate`() {
    writeMultiModuleSettingsFile()
    writeRootBuildFile()
    val moduleA = File(projectDir, "moduleA").also { it.mkdirs() }
    val moduleB = File(projectDir, "moduleB").also { it.mkdirs() }

    writeSubmoduleBuildFile(moduleA)
    writeSubmoduleBuildFile(moduleB) // default 100% thresholds
    writeSubmoduleSource(moduleA, covered = true)
    writeSubmoduleSource(moduleB, covered = false) // will fail at 100% threshold

    runner("koverGateReport").buildAndFail()

    val aggregateFile = File(projectDir, "build/reports/kover/gap-report.json")

    assertFalse(aggregateFile.exists(), "Aggregate report must not be generated when a submodule fails")
  }

  @Test
  fun `koverGateScopedReport succeeds even when coverage is below threshold`() {
    writeSettingsFile()
    writeBuildFile()
    writeSourceFile(covered = false)
    initGitRepo()

    val result = runner("koverGateScopedReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateScopedReport")?.outcome)
    assertNotNull(result.task(":koverGateGenerateReport"), "koverGateGenerateReport must have run")
    assertNull(result.task(":koverGateReport"), "Gate task must not run when only scoped report is requested")
  }

  @Test
  fun `auto-disables jacoco instrumentation when Kover useJacoco() is active`() {
    writeSettingsFile()
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            id("org.jetbrains.kotlinx.kover") version "0.9.8"
            id("com.commonsware.kovergate")
            jacoco
        }
        repositories { mavenCentral() }
        dependencies { testImplementation(kotlin("test")) }
        tasks.test { useJUnitPlatform() }
        kover { useJacoco() }
        koverGate {
            minLineCoverage.set(0)
            minBranchCoverage.set(0)
            minInstructionCoverage.set(0)
            minMethodCoverage.set(0)
            minClassCoverage.set(0)
        }
        """
          .trimIndent()
      )
    writeSourceFile(covered = true)
    writeTestFile()

    val result = runner("test", "koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)
    assertTrue(
      result.output.contains("KoverGate: disabled Gradle jacoco instrumentation on Test tasks"),
      "Expected auto-disable log message. Output:\n${result.output}",
    )
  }

  private fun writeTestFile() {
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
            @Test fun subtract() = assertEquals(1, calc.subtract(3, 2))
        }
        """
          .trimIndent()
      )
  }
}
