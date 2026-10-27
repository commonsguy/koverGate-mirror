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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Covers `xmlReportTask` naming one of Kover's Android variant tasks, which Kover registers after koverGate's own
 * `afterEvaluate` has run. Skipped when no Android SDK can be found.
 */
@Suppress("FunctionNaming", "MagicNumber")
class AndroidFunctionalTest {

  @TempDir lateinit var projectDir: File

  private fun findAndroidSdk(): File? {
    val home = System.getProperty("user.home")
    val candidates =
      listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        "$home/Library/Android/sdk",
        "$home/Android/Sdk",
      )

    return candidates.map { File(it) }.firstOrNull { it.isDirectory }
  }

  private fun writeAndroidProject(xmlReportTask: String) {
    val sdk = findAndroidSdk()

    assumeTrue(sdk != null, "No Android SDK found (ANDROID_HOME, ANDROID_SDK_ROOT, or the default location)")

    File(projectDir, "local.properties").writeText("sdk.dir=${sdk?.absolutePath}\n")
    writeAndroidBuildFiles(xmlReportTask)
    writeAndroidSources()
  }

  private fun writeAndroidBuildFiles(xmlReportTask: String) {
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                google()
                mavenCentral()
                gradlePluginPortal()
            }
        }
        dependencyResolutionManagement {
            repositories {
                google()
                mavenCentral()
            }
        }
        rootProject.name = "android-test"
        """
          .trimIndent()
      )
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("com.android.library") version "8.13.2"
            id("org.jetbrains.kotlin.android") version "2.2.21"
            id("org.jetbrains.kotlinx.kover") version "$TEST_KOVER_VERSION"
            id("com.commonsware.kovergate")
        }
        android {
            namespace = "com.example.lib"
            compileSdk = 35
            defaultConfig { minSdk = 24 }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        dependencies { testImplementation("junit:junit:4.13.2") }
        koverGate {
            minLineCoverage.set(0)
            minBranchCoverage.set(0)
            minInstructionCoverage.set(0)
            minMethodCoverage.set(0)
            minClassCoverage.set(0)
            xmlReportTask.set("$xmlReportTask")
        }
        """
          .trimIndent()
      )
  }

  private fun writeAndroidSources() {
    val mainDir = File(projectDir, "src/main/kotlin/com/example/lib").apply { mkdirs() }
    val testDir = File(projectDir, "src/test/kotlin/com/example/lib").apply { mkdirs() }

    File(mainDir, "Calculator.kt")
      .writeText(
        """
        package com.example.lib
        class Calculator {
            fun add(a: Int, b: Int): Int = a + b
        }
        """
          .trimIndent()
      )
    File(testDir, "CalculatorTest.kt")
      .writeText(
        """
        package com.example.lib
        import org.junit.Assert.assertEquals
        import org.junit.Test
        class CalculatorTest {
            @Test fun add() = assertEquals(5, Calculator().add(2, 3))
        }
        """
          .trimIndent()
      )
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  @Test
  fun `xmlReportTask naming a Kover Android variant task is resolved`() {
    writeAndroidProject("koverXmlReportDebug")

    val doctor = runner("koverGateDoctor").build()

    assertEquals(TaskOutcome.SUCCESS, doctor.task(":koverGateDoctor")?.outcome)
    assertTrue(doctor.output.contains("reportDebug.xml"), "Doctor output:\n${doctor.output}")
    assertTrue(doctor.output.contains("Report-contention guard: FULL"), "Doctor output:\n${doctor.output}")

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverXmlReportDebug")?.outcome)
    assertNull(result.task(":koverXmlReport"))
    assertNull(result.task(":testReleaseUnitTest"))

    val report = Json.decodeFromString<GapReport>(File(projectDir, "build/reports/kover/gap-report.json").readText())

    assertNotNull(report.source)
    assertTrue(report.source?.xmlPath.orEmpty().endsWith("reportDebug.xml"), "Source: ${report.source}")
  }

  @Test
  fun `root library with an app subproject is gated as a hybrid`() {
    writeAndroidProject("koverXmlReportDebug")
    File(projectDir, "settings.gradle.kts").appendText("\ninclude(\":app\")\n")
    File(projectDir, "app").mkdirs()
    File(projectDir, "app/build.gradle.kts").writeText("")

    val result = runner("koverGateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateReport")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":koverXmlReportDebug")?.outcome)
  }

  @Test
  fun `misspelled Android variant task fails the gate listing the available tasks`() {
    writeAndroidProject("koverXmlReportDebg")

    val help = runner("help").build()

    assertEquals(TaskOutcome.SUCCESS, help.task(":help")?.outcome)

    val result = runner("koverGateReport").buildAndFail()

    assertTrue(
      result.output.contains("KoverGate: xmlReportTask 'koverXmlReportDebg' not found"),
      "Output:\n${result.output}",
    )
    assertTrue(result.output.contains("koverXmlReportDebug"), "Output:\n${result.output}")
  }
}
