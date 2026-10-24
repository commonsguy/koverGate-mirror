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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")
class ScopedIgnoreSpaceChangeFunctionalTest {

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

  private fun git(vararg args: String) {
    val process = ProcessBuilder(listOf("git") + args).directory(projectDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()

    check(code == 0) { "git ${args.joinToString(" ")} failed ($code): $output" }
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  /**
   * Writes a `Calculator.kt` whose untested `subtract` is either indented as usual or re-indented by four more spaces
   * (identical bytecode), with an optional new untested top-level `multiply` after the class. Line layout: `subtract`
   * occupies lines 4-7 and `multiply`, when present, is line 9.
   */
  private fun writeReindentableCalculator(reindented: Boolean, includeMultiply: Boolean) {
    val srcDir = File(projectDir, "src/main/kotlin/com/example")
    val indent = if (reindented) "    " else ""
    val multiply = if (includeMultiply) "\nfun multiply(a: Int, b: Int): Int = a * b" else ""
    val calculator =
      """
      package com.example
      class Calculator {
          fun add(a: Int, b: Int): Int = a + b
      ${indent}    fun subtract(a: Int, b: Int): Int {
      ${indent}        val result = a - b
      ${indent}        return result
      ${indent}    }
      }
      """
        .trimIndent()

    srcDir.mkdirs()
    File(srcDir, "Calculator.kt").writeText(calculator + multiply)
  }

  /** Commits the un-reindented base on `main`, then a feature commit that re-indents `subtract` and adds `multiply`. */
  private fun setUpReindentedBranch() {
    writeSettingsFile()
    writeBuildFile()
    writeReindentableCalculator(reindented = false, includeMultiply = false)

    val testDir = File(projectDir, "src/test/kotlin/com/example")

    testDir.mkdirs()
    File(testDir, "CalculatorTest.kt")
      .writeText(
        """
        package com.example
        import kotlin.test.Test
        import kotlin.test.assertEquals
        class CalculatorTest {
            @Test fun add() = assertEquals(5, Calculator().add(2, 3))
        }
        """
          .trimIndent()
      )

    git("init")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial commit: untested subtract")

    git("checkout", "-b", "feature")
    writeReindentableCalculator(reindented = true, includeMultiply = true)
    git("add", "src/main/kotlin/com/example/Calculator.kt")
    git("commit", "-m", "Re-indent subtract and add multiply")

    runner("test", "koverGateReport").build()
  }

  private fun readScopedReport(): GapReport =
    Json.decodeFromString<GapReport>(File(projectDir, "build/reports/kover/scoped-gap-report.json").readText())

  private fun readScopedUncoveredLines(): List<Int> = readScopedReport().files.single().uncoveredLines

  @Test
  fun `scoped report records the scope settings`() {
    setUpReindentedBranch()

    runner("koverGateScopedReport", "-PkoverGateBase=main", "-PkoverGateIgnoreSpaceChange=true").build()

    val ignoring = readScopedReport().scope

    runner("koverGateScopedReport", "-PkoverGateBase=main").build()

    val keeping = readScopedReport().scope

    assertEquals(ScopeProvenance("branch", true), ignoring)
    assertEquals(ScopeProvenance("branch", false), keeping)
  }

  @Test
  fun `scoped report keeps re-indented lines by default`() {
    setUpReindentedBranch()

    runner("koverGateScopedReport", "-PkoverGateBase=main").build()

    val uncovered = readScopedUncoveredLines()

    assertTrue(uncovered.any { it in 4..7 }, "Re-indented subtract should be in scope by default. Got: $uncovered")
    assertTrue(9 in uncovered, "New multiply should be in scope. Got: $uncovered")
  }

  @Test
  fun `scoped report drops re-indented lines with the ignore space change property`() {
    setUpReindentedBranch()

    runner("koverGateScopedReport", "-PkoverGateBase=main", "-PkoverGateIgnoreSpaceChange=true").build()

    val uncovered = readScopedUncoveredLines()

    assertTrue(uncovered.none { it in 4..7 }, "Re-indented subtract should be out of scope. Got: $uncovered")
    assertTrue(9 in uncovered, "New multiply should stay in scope. Got: $uncovered")
  }

  @Test
  fun `scoped report drops re-indented lines with the ignore space change task option`() {
    setUpReindentedBranch()

    runner("koverGateScopedReport", "-PkoverGateBase=main", "--ignore-space-change").build()

    val uncovered = readScopedUncoveredLines()

    assertTrue(uncovered.none { it in 4..7 }, "Re-indented subtract should be out of scope. Got: $uncovered")
    assertTrue(9 in uncovered, "New multiply should stay in scope. Got: $uncovered")
  }

  @Test
  fun `ignore space change toggle is honored under the configuration cache`() {
    setUpReindentedBranch()

    runner(
        "koverGateScopedReport",
        "--configuration-cache",
        "-PkoverGateBase=main",
        "-PkoverGateIgnoreSpaceChange=true",
      )
      .build()

    val ignoring = readScopedUncoveredLines()

    runner("koverGateScopedReport", "--configuration-cache", "-PkoverGateBase=main").build()

    val keeping = readScopedUncoveredLines()

    assertTrue(ignoring.none { it in 4..7 }, "Run with the property should drop subtract. Got: $ignoring")
    assertTrue(keeping.any { it in 4..7 }, "Run without the property should keep subtract. Got: $keeping")
  }
}
