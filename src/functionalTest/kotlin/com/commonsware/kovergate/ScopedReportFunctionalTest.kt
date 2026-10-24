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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("FunctionNaming", "LongMethod", "TooManyFunctions", "MagicNumber")
class ScopedReportFunctionalTest {

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
  }

  private fun writeCalculatorSource(includeMultiply: Boolean) {
    val srcDir = File(projectDir, "src/main/kotlin/com/example")

    srcDir.mkdirs()
    val code =
      if (includeMultiply) {
        """
        package com.example
        class Calculator {
            fun add(a: Int, b: Int): Int = a + b
            fun subtract(a: Int, b: Int): Int = a - b
            fun multiply(a: Int, b: Int): Int = a * b
        }
        """
      } else {
        """
        package com.example
        class Calculator {
            fun add(a: Int, b: Int): Int = a + b
            fun subtract(a: Int, b: Int): Int = a - b
        }
        """
      }

    File(srcDir, "Calculator.kt").writeText(code.trimIndent())
  }

  private fun writeCalculatorTest(testMultiply: Boolean) {
    val testDir = File(projectDir, "src/test/kotlin/com/example")

    testDir.mkdirs()
    val testCode =
      if (testMultiply) {
        """
        package com.example
        import kotlin.test.Test
        import kotlin.test.assertEquals
        class CalculatorTest {
            private val calc = Calculator()
            @Test fun add() = assertEquals(5, calc.add(2, 3))
            @Test fun subtract() = assertEquals(1, calc.subtract(3, 2))
            @Test fun multiply() = assertEquals(6, calc.multiply(2, 3))
        }
        """
      } else {
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
      }

    File(testDir, "CalculatorTest.kt").writeText(testCode.trimIndent())
  }

  private fun git(vararg args: String) {
    val process = ProcessBuilder(listOf("git") + args).directory(projectDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()

    check(code == 0) { "git ${args.joinToString(" ")} failed ($code): $output" }
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  private fun createSubproject(name: String, functionName: String, uncovered: Boolean) {
    val dir = File(projectDir, name)

    dir.mkdirs()
    File(dir, "build.gradle.kts")
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
            minLineCoverage.set(0)
            minBranchCoverage.set(0)
            minInstructionCoverage.set(0)
            minMethodCoverage.set(0)
            minClassCoverage.set(0)
        }
        """
          .trimIndent()
      )

    val srcDir = File(dir, "src/main/kotlin/com/example/$name")

    srcDir.mkdirs()
    val srcFile = File(srcDir, "Function${name.uppercase()}.kt")
    val baseFn = "${functionName}()"
    val code =
      if (uncovered) {
        """
        package com.example.$name
        class Function${name.uppercase()} {
            fun ${baseFn}: String = "result"
            fun other(): String = "uncovered"
        }
        """
      } else {
        """
        package com.example.$name
        class Function${name.uppercase()} {
            fun ${baseFn}: String = "result"
        }
        """
      }

    srcFile.writeText(code.trimIndent())

    val testDir = File(dir, "src/test/kotlin/com/example/$name")

    testDir.mkdirs()
    File(testDir, "Function${name.uppercase()}Test.kt")
      .writeText(
        """
        package com.example.$name
        import kotlin.test.Test
        import kotlin.test.assertEquals
        class Function${name.uppercase()}Test {
            @Test fun test() = assertEquals("result", Function${name.uppercase()}().${baseFn})
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun `scoped report filters to committed changed lines`() {
    writeSettingsFile()
    writeBuildFile()
    writeCalculatorSource(includeMultiply = false)
    writeCalculatorTest(testMultiply = false)

    // Initialize git and create main branch
    git("init")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial commit: add/subtract with full coverage")

    // Create feature branch and add uncovered multiply
    git("checkout", "-b", "feature")
    writeCalculatorSource(includeMultiply = true)
    git("add", "src/main/kotlin/com/example/Calculator.kt")
    git("commit", "-m", "Add multiply (uncovered)")

    // Run tests and scoped report
    runner("test", "koverGateReport").build()
    val result = runner("koverGateScopedReport", "-PkoverGateBase=main").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateScopedReport")?.outcome)

    // Check scoped report
    val jsonFile = File(projectDir, "build/reports/kover/scoped-gap-report.json")

    assertTrue(jsonFile.exists(), "Scoped report should be written")

    val json = jsonFile.readText()
    val report = Json.decodeFromString<GapReport>(json)

    // multiply is uncovered and was added on the branch → should appear in scoped report
    assertTrue(report.files.isNotEmpty(), "Scoped report should contain gaps for the new multiply method")
    assertEquals(1, report.files.size, "Should have one file with gaps")

    val calcGap = report.files[0]

    assertEquals("Calculator.kt", calcGap.name)
    assertTrue(calcGap.uncoveredLines.isNotEmpty(), "Should have uncovered lines in multiply")

    // subtract is uncovered in original but not changed → should NOT appear
    // (this is verified by the gap only being in multiply line, not earlier lines)
    val uncoveredLines = calcGap.uncoveredLines
    assertTrue(
      uncoveredLines.all { it >= 5 }, // multiply is roughly at line 5+
      "Uncovered lines should only be in multiply, not subtract region. Got: $uncoveredLines",
    )

    // Scoped report has empty metrics
    assertTrue(report.metrics.isEmpty(), "Scoped report should have empty metrics")

    // passed should be false because gaps remain
    assertFalse(report.passed, "Scoped report should show failed because in-scope gaps exist")

    // Output logs the status
    assertTrue(result.output.contains("Scoped coverage gate"), "Output should contain scoped gate message")
  }

  @Test
  fun `scoped report includes uncommitted changes`() {
    writeSettingsFile()
    writeBuildFile()
    writeCalculatorSource(includeMultiply = false)
    writeCalculatorTest(testMultiply = false)

    // Initialize git and create main branch
    git("init")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial commit: add/subtract with full coverage")

    // Create feature branch
    git("checkout", "-b", "feature")

    // Add multiply but do NOT commit it (uncommitted change)
    writeCalculatorSource(includeMultiply = true)

    // Run tests and scoped report
    runner("test", "koverGateReport").build()
    val result = runner("koverGateScopedReport", "-PkoverGateBase=main").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateScopedReport")?.outcome)

    // Check that uncommitted multiply is detected
    val json = File(projectDir, "build/reports/kover/scoped-gap-report.json").readText()
    val report = Json.decodeFromString<GapReport>(json)

    // multiply is uncommitted but should still appear because we diff against working tree
    assertTrue(report.files.isNotEmpty(), "Scoped report should detect uncommitted changes on the working tree")

    val uncoveredLines = report.files[0].uncoveredLines

    assertTrue(
      uncoveredLines.all { it >= 5 },
      "Uncovered multiply should appear even though not yet committed. Got: $uncoveredLines",
    )
  }

  private fun writeClassifierSource() {
    val srcDir = File(projectDir, "src/main/kotlin/com/example")

    srcDir.mkdirs()
    File(srcDir, "Classifier.kt")
      .writeText(
        """
        package com.example
        class Classifier {
            fun classify(n: Int): String = if (n > 0) "pos" else "neg"
        }
        """
          .trimIndent()
      )
  }

  private fun writeClassifierTest() {
    val testDir = File(projectDir, "src/test/kotlin/com/example")

    testDir.mkdirs()
    File(testDir, "ClassifierTest.kt")
      .writeText(
        """
        package com.example
        import kotlin.test.Test
        import kotlin.test.assertEquals
        class ClassifierTest {
            @Test fun positive() = assertEquals("pos", Classifier().classify(1))
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun `scoped report includes a partially covered line`() {
    writeSettingsFile()
    writeBuildFile()
    writeCalculatorSource(includeMultiply = false)
    writeCalculatorTest(testMultiply = false)

    // Initialize git and create main branch
    git("init")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial commit: add/subtract with full coverage")

    // Create feature branch
    git("checkout", "-b", "feature")

    // Add a partially covered line (only one branch of the `if` is exercised), uncommitted
    writeClassifierSource()
    writeClassifierTest()

    // Run tests and scoped report
    runner("test", "koverGateReport").build()
    val result = runner("koverGateScopedReport", "-PkoverGateBase=main").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateScopedReport")?.outcome)

    val json = File(projectDir, "build/reports/kover/scoped-gap-report.json").readText()
    val report = Json.decodeFromString<GapReport>(json)

    val classifierGap = report.files.find { it.name == "Classifier.kt" }

    assertTrue(classifierGap != null, "Classifier.kt should appear in the scoped report")
    assertTrue(
      classifierGap!!.partiallyCoveredLines.isNotEmpty(),
      "The partially covered classify() line should be in scope. Got: $classifierGap",
    )
    assertFalse(report.passed, "Scoped report should fail because an in-scope partially covered line exists")
  }

  @Test
  fun `scoped report includes an untracked file`() {
    writeSettingsFile()
    writeBuildFile()
    writeCalculatorSource(includeMultiply = false)
    writeCalculatorTest(testMultiply = false)

    // Initialize git and create main branch
    git("init")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial commit: add/subtract with full coverage")

    // Create feature branch
    git("checkout", "-b", "feature")

    // Add a brand-new, never-`git add`-ed source file with an uncovered method
    val srcDir = File(projectDir, "src/main/kotlin/com/example")

    srcDir.mkdirs()
    File(srcDir, "Divider.kt")
      .writeText(
        """
        package com.example
        class Divider {
            fun divide(a: Int, b: Int): Int = a / b
        }
        """
          .trimIndent()
      )

    // Run tests and scoped report
    runner("test", "koverGateReport").build()
    val result = runner("koverGateScopedReport", "-PkoverGateBase=main").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateScopedReport")?.outcome)

    // Check that the untracked file is detected
    val json = File(projectDir, "build/reports/kover/scoped-gap-report.json").readText()
    val report = Json.decodeFromString<GapReport>(json)

    val dividerGap = report.files.find { it.name == "Divider.kt" }

    assertTrue(dividerGap != null, "Untracked Divider.kt should appear in the scoped report")
    assertTrue(dividerGap!!.uncoveredLines.isNotEmpty(), "Untracked file's uncovered lines should be in scope")
    assertFalse(report.passed, "Scoped report should fail because the untracked file has in-scope gaps")
    assertTrue(result.output.contains("untracked"), "Output should note the untracked file count")
  }

  @Test
  fun `scoped report includes an untracked file in a subproject`() {
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
        }
        rootProject.name = "multi-module"
        include(":a", ":b")
        """
          .trimIndent()
      )

    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("com.commonsware.kovergate")
        }
        """
          .trimIndent()
      )

    createSubproject("a", "do1", uncovered = false)
    createSubproject("b", "do2", uncovered = false)

    // Initialize git with both modules
    git("init")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial commit")

    // Create feature branch
    git("checkout", "-b", "feature")

    // Add a brand-new, never-`git add`-ed source file inside subproject :a
    val aaSrcDir = File(projectDir, "a/src/main/kotlin/com/example/a")

    File(aaSrcDir, "ExtraA.kt")
      .writeText(
        """
        package com.example.a
        class ExtraA {
            fun uncovered(): String = "uncovered"
        }
        """
          .trimIndent()
      )

    // Run tests and scoped aggregate report
    runner("test", "koverGateReport").build()
    val result = runner("koverGateScopedReport", "-PkoverGateBase=main").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateScopedReport")?.outcome)

    val jsonFile = File(projectDir, "build/reports/kover/scoped-gap-report.json")
    val aggregate = Json.decodeFromString<AggregateGapReport>(jsonFile.readText())

    val moduleA = aggregate.modules.find { it.module == ":a" }
    val moduleB = aggregate.modules.find { it.module == ":b" }

    assertTrue(
      moduleA?.files?.any { it.name == "ExtraA.kt" } == true,
      "Untracked ExtraA.kt in subproject :a should appear in scope. Module :a report: $moduleA",
    )
    assertFalse(moduleA?.passed ?: true, "Module :a should fail because its untracked file has in-scope gaps")
    assertTrue(moduleB?.passed == true, "Module :b should pass; it has no untracked or changed files")
    assertFalse(aggregate.passed, "Aggregate should report failure due to :a's untracked file")
  }

  @Test
  fun `scoped report passes when all changed lines are covered`() {
    writeSettingsFile()
    writeBuildFile()
    writeCalculatorSource(includeMultiply = false)
    writeCalculatorTest(testMultiply = false)

    // Initialize git and create main branch
    git("init")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial commit: add/subtract with full coverage")

    // Create feature branch and add covered multiply
    git("checkout", "-b", "feature")
    writeCalculatorSource(includeMultiply = true)
    writeCalculatorTest(testMultiply = true)
    git("add", "src/main/kotlin/com/example/Calculator.kt", "src/test/kotlin/com/example/CalculatorTest.kt")
    git("commit", "-m", "Add multiply with full test coverage")

    // Run tests and scoped report
    runner("test", "koverGateReport").build()
    val result = runner("koverGateScopedReport", "-PkoverGateBase=main").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateScopedReport")?.outcome)

    // All changed lines are covered
    val json = File(projectDir, "build/reports/kover/scoped-gap-report.json").readText()
    val report = Json.decodeFromString<GapReport>(json)

    assertTrue(report.files.isEmpty(), "No gaps should exist when all changed lines are covered")
    assertTrue(report.passed, "Scoped report should pass when all changed lines are covered")
  }

  @Test
  fun `scoped report is compatible with configuration cache`() {
    writeSettingsFile()
    writeBuildFile()
    writeCalculatorSource(includeMultiply = false)
    writeCalculatorTest(testMultiply = false)

    // Initialize git and create main branch
    git("init")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial")

    git("checkout", "-b", "feature")
    writeCalculatorSource(includeMultiply = true)
    git("add", "src/main/kotlin/com/example/Calculator.kt")
    git("commit", "-m", "Add multiply")

    // First run: populates the configuration cache
    runner("koverGateScopedReport", "--configuration-cache", "-PkoverGateBase=main").build()

    // Second run: must reuse the configuration cache (same task set = same cache key)
    val result = runner("koverGateScopedReport", "--configuration-cache", "-PkoverGateBase=main").build()

    assertTrue(
      result.output.contains("Reusing configuration cache"),
      "Expected configuration cache to be reused. Output:\n${result.output}",
    )
  }

  @Test
  fun `multi-module aggregate scoped report merges submodule reports`() {
    // Create root-level settings with two subprojects
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
        }
        rootProject.name = "multi-module"
        include(":a", ":b")
        """
          .trimIndent()
      )

    // Root build file: applies kovergate, no Kover
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("com.commonsware.kovergate")
        }
        """
          .trimIndent()
      )

    // Create both subprojects
    createSubproject("a", "do1", uncovered = false)
    createSubproject("b", "do2", uncovered = false)

    // Initialize git with both modules
    git("init")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial commit")

    // Create feature branch and add uncovered functions
    git("checkout", "-b", "feature")

    // Add uncovered function to :a
    val aaSrcDir = File(projectDir, "a/src/main/kotlin/com/example/a")

    File(aaSrcDir, "FunctionA.kt")
      .writeText(
        """
        package com.example.a
        class FunctionA {
            fun do1(): String = "result"
            fun do2(): String = "uncovered"
        }
        """
          .trimIndent()
      )

    // Add uncovered function to :b
    val bSrcDir = File(projectDir, "b/src/main/kotlin/com/example/b")

    File(bSrcDir, "FunctionB.kt")
      .writeText(
        """
        package com.example.b
        class FunctionB {
            fun do2(): String = "result"
            fun do3(): String = "uncovered"
        }
        """
          .trimIndent()
      )

    git("add", "a/src/main/kotlin/com/example/a/FunctionA.kt", "b/src/main/kotlin/com/example/b/FunctionB.kt")
    git("commit", "-m", "Add uncovered functions")

    // Run tests and scoped aggregate report
    runner("test", "koverGateReport").build()
    val result = runner("koverGateScopedReport", "-PkoverGateBase=main").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateScopedReport")?.outcome)

    // Check aggregate report
    val jsonFile = File(projectDir, "build/reports/kover/scoped-gap-report.json")

    assertTrue(jsonFile.exists(), "Aggregate scoped report should be written at root")

    val json = jsonFile.readText()
    val aggregate = Json.decodeFromString<AggregateGapReport>(json)

    // Both modules should be represented
    assertEquals(2, aggregate.modules.size, "Should have reports from both modules")

    // Both modules should have gaps
    assertTrue(aggregate.modules.all { !it.passed }, "Both modules should have in-scope gaps")

    // Overall aggregate should fail
    assertFalse(aggregate.passed, "Aggregate should report failure when any module has gaps")

    // Each module report should have empty metrics
    assertTrue(aggregate.modules.all { it.metrics.isEmpty() }, "All module reports should have empty metrics")

    // Check that both modules have appropriate file gaps
    val moduleA = aggregate.modules.find { it.module == ":a" }
    val moduleB = aggregate.modules.find { it.module == ":b" }

    assertEquals(1, moduleA?.files?.size, "Module :a should have one file with gaps")
    assertEquals(1, moduleB?.files?.size, "Module :b should have one file with gaps")

    // Each module entry carries the scope block
    assertTrue(aggregate.modules.all { it.scope != null }, "All module reports should record their scope")
  }
}
