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

@Suppress("FunctionNaming", "MagicNumber")
class ContentionFunctionalTest {

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
        rootProject.name = "contention-test"
        """
          .trimIndent()
      )
  }

  /**
   * A minimal JaCoCo-shaped XML report body, embedded verbatim into a generated `build.gradle.kts`'s `writeText` call.
   *
   * Built line-by-line (not via a nested `trimIndent()`-ed raw string) so every line is flush-left regardless of where
   * the caller's own raw string interpolates this value — a nested block whose lines carry mismatched indentation
   * defeats the caller's `trimIndent()`, leaving stray whitespace before `<?xml` and breaking the generated XML.
   */
  private fun sampleJacocoXml(reportName: String, comment: String? = null): String {
    val header = listOfNotNull("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", comment)
    val body =
      listOf(
        "<report name=\"$reportName\">",
        "  <package name=\"com/example\">",
        "    <sourcefile name=\"Calculator.kt\">",
        "      <line nr=\"3\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>",
        "      <line nr=\"4\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>",
        "      <counter type=\"LINE\" missed=\"0\" covered=\"2\"/>",
        "      <counter type=\"METHOD\" missed=\"0\" covered=\"2\"/>",
        "      <counter type=\"CLASS\" missed=\"0\" covered=\"1\"/>",
        "      <counter type=\"INSTRUCTION\" missed=\"0\" covered=\"6\"/>",
        "    </sourcefile>",
        "  </package>",
        "  <counter type=\"LINE\" missed=\"0\" covered=\"2\"/>",
        "  <counter type=\"METHOD\" missed=\"0\" covered=\"2\"/>",
        "  <counter type=\"CLASS\" missed=\"0\" covered=\"1\"/>",
        "  <counter type=\"INSTRUCTION\" missed=\"0\" covered=\"6\"/>",
        "</report>",
      )

    return (header + body).joinToString("\n")
  }

  private fun writeBuildFile(
    clobber: Boolean,
    failOnContendedReport: Boolean = false,
    clobberAfterRead: Boolean = false,
  ) {
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            id("com.commonsware.kovergate")
        }
        repositories { mavenCentral() }

        val writeXml = tasks.register("writeXml") {
            doLast {
                val f = layout.projectDirectory.file("external/jacoco.xml").asFile
                f.parentFile.mkdirs()
                f.writeText(${'"'}${'"'}${'"'}
${sampleJacocoXml("producer")}
                ${'"'}${'"'}${'"'}.trimIndent())
            }
        }

        val clobberXml = tasks.register("clobberXml") {
            dependsOn(writeXml)
            doLast {
                val f = layout.projectDirectory.file("external/jacoco.xml").asFile
                f.writeText(${'"'}${'"'}${'"'}
${sampleJacocoXml("clobberer", "<!-- clobbered by a different task -->")}
                ${'"'}${'"'}${'"'}.trimIndent())
            }
        }

        koverGate {
            minLineCoverage.set(50)
            minBranchCoverage.set(50)
            minInstructionCoverage.set(50)
            minMethodCoverage.set(50)
            minClassCoverage.set(50)
            xmlReportFile.set(layout.projectDirectory.file("external/jacoco.xml"))
            xmlReportTask.set("writeXml")
            failOnContendedReport.set($failOnContendedReport)
        }

        ${if (clobber) "tasks.named(\"koverGateGenerateReport\") { dependsOn(clobberXml) }" else ""}
        ${if (clobberAfterRead) "tasks.named(\"clobberXml\") { mustRunAfter(\"koverGateGenerateReport\") }" else ""}
        """
          .trimIndent()
      )
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(projectDir).withArguments(*args, "--stacktrace").withPluginClasspath()

  private fun readReport(): GapReport =
    Json.decodeFromString(File(projectDir, "build/reports/kover/gap-report.json").readText())

  @Test
  fun `a before-read clobber warns but the build still passes`() {
    writeSettingsFile()
    writeBuildFile(clobber = true)

    val result = runner("koverGateGenerateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateGenerateReport")?.outcome)
    assertTrue(
      result.output.contains("REWRITTEN") && result.output.contains("verdict may be wrong"),
      "Expected a before-read contention warning in output:\n${result.output}",
    )
  }

  @Test
  fun `a before-read clobber fails the build when failOnContendedReport is set`() {
    writeSettingsFile()
    writeBuildFile(clobber = true, failOnContendedReport = true)

    val result = runner("koverGateGenerateReport").buildAndFail()

    assertEquals(TaskOutcome.FAILED, result.task(":koverGateGenerateReport")?.outcome)
    assertTrue(result.output.contains("verdict may be wrong"))
  }

  @Test
  fun `an unclobbered build produces no contention warning`() {
    writeSettingsFile()
    writeBuildFile(clobber = false)

    val result = runner("koverGateGenerateReport").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateGenerateReport")?.outcome)
    assertFalse(result.output.contains("REWRITTEN"))

    val report = readReport()

    assertFalse(report.source!!.rewrittenBeforeRead)
  }

  @Test
  fun `a before-read clobber records rewrittenBeforeRead in the gap report`() {
    writeSettingsFile()
    writeBuildFile(clobber = true)

    runner("koverGateGenerateReport").build()

    val report = readReport()

    assertTrue(report.source!!.rewrittenBeforeRead)
  }

  @Test
  fun `a clobber staged after the gate read warns at build end without failing`() {
    writeSettingsFile()
    writeBuildFile(clobber = false, clobberAfterRead = true)

    val result = runner("koverGateGenerateReport", "clobberXml").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":koverGateGenerateReport")?.outcome)
    assertFalse(result.output.contains("verdict may be wrong"))
    assertTrue(
      result.output.contains("contended"),
      "Expected an after-read contention warning in output:\n${result.output}",
    )

    val report = readReport()

    assertFalse(report.source!!.rewrittenBeforeRead)
  }
}
