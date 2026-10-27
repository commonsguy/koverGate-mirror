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

/**
 * Covers root projects that have subprojects: the hybrid role (a root that is also a library), the aggregate-only role,
 * the misordered-plugins guard and the `koverGateRootMode` override.
 */
@Suppress("FunctionNaming", "TooManyFunctions", "MagicNumber")
class RootModeFunctionalTest {

  @TempDir lateinit var projectDir: File

  private val gapReportFile
    get() = File(projectDir, "build/reports/kover/gap-report.json")

  private val aggregateReportFile
    get() = File(projectDir, "build/reports/kover/aggregate-gap-report.json")

  private fun writeSettingsFile(vararg includes: String) {
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
        }
        rootProject.name = "root-mode-test"
        include(${includes.joinToString { "\":$it\"" }})
        """
          .trimIndent()
      )
  }

  private fun writeRootBuildFile(
    plugins: String = ORDERED_PLUGINS,
    extra: String = "",
    withTestSetup: Boolean = true,
  ) {
    val testSetup =
      if (withTestSetup) {
        """
        repositories { mavenCentral() }
        dependencies { testImplementation(kotlin("test")) }
        tasks.test { useJUnitPlatform() }
        """
          .trimIndent()
      } else {
        ""
      }

    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            $plugins
        }
        $testSetup
        $extra
        """
          .trimIndent()
      )
  }

  private fun writeCalculator(dir: File, covered: Boolean) {
    val srcDir = File(dir, "src/main/kotlin/com/example").apply { mkdirs() }
    val testDir = File(dir, "src/test/kotlin/com/example").apply { mkdirs() }

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

    val subtractTest = if (covered) "@Test fun subtract() = assertEquals(1, calc.subtract(3, 2))" else ""

    File(testDir, "CalculatorTest.kt")
      .writeText(
        """
        package com.example
        import kotlin.test.Test
        import kotlin.test.assertEquals
        class CalculatorTest {
            private val calc = Calculator()
            @Test fun add() = assertEquals(5, calc.add(2, 3))
            $subtractTest
        }
        """
          .trimIndent()
      )
  }

  private fun writeKoverGateSubproject(name: String, kotlinVersion: String = "") {
    val dir = File(projectDir, name).apply { mkdirs() }

    File(dir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm")$kotlinVersion
            id("org.jetbrains.kotlinx.kover")
            id("com.commonsware.kovergate")
        }
        repositories { mavenCentral() }
        dependencies { testImplementation(kotlin("test")) }
        tasks.test { useJUnitPlatform() }
        """
          .trimIndent()
      )
    writeCalculator(dir, covered = true)
  }

  private fun git(vararg args: String) {
    val process = ProcessBuilder(listOf("git") + args).directory(projectDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()

    check(code == 0) { "git ${args.joinToString(" ")} failed ($code): $output" }
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  private fun writeHybridFixture(rootPlugins: String = ORDERED_PLUGINS, extra: String = "") {
    writeSettingsFile("sub")
    writeRootBuildFile(rootPlugins, extra)
    writeCalculator(projectDir, covered = true)
    writeKoverGateSubproject("sub")
  }

  @Test
  fun `auto hybrid aggregate holds the root and the subproject`() {
    writeHybridFixture()

    val result = runner("koverGateAggregateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateAggregateReport")?.outcome)

    val aggregate = Json.decodeFromString<AggregateGapReport>(aggregateReportFile.readText())

    assertTrue(aggregate.passed)
    assertEquals(setOf(":", ":sub"), aggregate.modules.map { it.module }.toSet())

    val rootReport = Json.decodeFromString<GapReport>(gapReportFile.readText())

    assertEquals(":", rootReport.module)
  }

  @Test
  fun `auto hybrid writes a scoped report for the root's own classes`() {
    writeHybridFixture()
    git("init", "-b", "main")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test User")
    git("add", ".")
    git("commit", "-m", "Initial commit")

    val result = runner(":koverGateScopedReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateScopedReport")?.outcome)
    assertTrue(File(projectDir, "build/reports/kover/scoped-gap-report.json").exists())
  }

  @Test
  fun `auto hybrid doctor names the root's Kover XML`() {
    writeHybridFixture()

    val result = runner(":koverGateDoctor").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateDoctor")?.outcome)
    assertTrue(result.output.contains("Kover XML:"), "Doctor output:\n${result.output}")
    assertTrue(result.output.contains("reportJvm.xml"), "Doctor output:\n${result.output}")
  }

  @Test
  fun `merge-only root with Kover and no code plugin stays aggregate`() {
    writeSettingsFile("sub")
    writeRootBuildFile(
      plugins =
        """
        id("org.jetbrains.kotlinx.kover") version "$TEST_KOVER_VERSION"
        id("com.commonsware.kovergate")
        """
          .trimIndent(),
      withTestSetup = false,
    )
    writeKoverGateSubproject("sub", kotlinVersion = " version \"2.0.21\"")
    assertAggregateOnly()
  }

  @Test
  fun `misordered plugins under auto fail with guidance`() {
    writeHybridFixture(rootPlugins = MISORDERED_PLUGINS)

    val result = runner("help").buildAndFail()

    assertTrue(result.output.contains("after 'com.commonsware.kovergate'"), "Output:\n${result.output}")
    assertTrue(result.output.contains("koverGateRootMode=hybrid"), "Output:\n${result.output}")
  }

  @Test
  fun `misordered plugins with hybrid override succeed`() {
    writeHybridFixture(rootPlugins = MISORDERED_PLUGINS)

    val result = runner("koverGateAggregateReport", "-PkoverGateRootMode=hybrid").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateAggregateReport")?.outcome)
  }

  @Test
  fun `aggregate override on a hybrid-shaped root stays aggregate-only`() {
    writeHybridFixture()
    assertAggregateOnly("-PkoverGateRootMode=aggregate")
  }

  @Test
  fun `bogus root mode fails naming the valid values`() {
    writeHybridFixture()

    val result = runner("help", "-PkoverGateRootMode=bogus").buildAndFail()

    assertTrue(result.output.contains("koverGateRootMode"), "Output:\n${result.output}")
    assertTrue(result.output.contains("auto, aggregate, hybrid"), "Output:\n${result.output}")
  }

  @Test
  fun `kage shape with a nested subproject that does not apply koverGate`() {
    writeSettingsFile("examples:cli")
    writeRootBuildFile(
      extra =
        """
        koverGate {
            minLineCoverage.set(100)
            minBranchCoverage.set(0)
        }
        """
          .trimIndent()
    )
    writeCalculator(projectDir, covered = true)

    val cli = File(projectDir, "examples/cli").apply { mkdirs() }

    File(cli, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm")
            application
        }
        repositories { mavenCentral() }
        dependencies { implementation(project(":")) }
        application { mainClass.set("com.example.MainKt") }
        """
          .trimIndent()
      )

    val passing = runner("koverGateAggregateReport").build()

    assertEquals(TaskOutcome.SUCCESS, passing.task(":koverGateAggregateReport")?.outcome)

    val aggregate = Json.decodeFromString<AggregateGapReport>(aggregateReportFile.readText())

    assertEquals(listOf(":"), aggregate.modules.map { it.module })

    writeCalculator(projectDir, covered = false)

    val failing = runner("koverGateReport").buildAndFail()

    assertEquals(TaskOutcome.FAILED, failing.task(":koverGateReport")?.outcome)
  }

  private fun assertAggregateOnly(vararg args: String) {
    val help = runner("help", "--task", "koverGateReport", *args).build()

    assertTrue(help.output.contains("KoverGateAggregateReportTask"), "Output:\n${help.output}")

    val missing = runner("help", "--task", "koverGateAggregateReport", *args).buildAndFail()

    assertFalse(missing.output.contains("Type  KoverGateAggregateReportTask"), "Output:\n${missing.output}")
  }

  private companion object {
    val ORDERED_PLUGINS =
      """
      kotlin("jvm") version "2.0.21"
      id("org.jetbrains.kotlinx.kover") version "$TEST_KOVER_VERSION"
      id("com.commonsware.kovergate")
      """
        .trimIndent()

    val MISORDERED_PLUGINS =
      """
      id("com.commonsware.kovergate")
      kotlin("jvm") version "2.0.21"
      id("org.jetbrains.kotlinx.kover") version "$TEST_KOVER_VERSION"
      """
        .trimIndent()
  }
}
