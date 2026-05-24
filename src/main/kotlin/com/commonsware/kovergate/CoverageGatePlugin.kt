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

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

/** Gradle plugin that configures coverage gate checking against Kover XML reports. */
public class CoverageGatePlugin : Plugin<Project> {
  /**
   * Applies the plugin. If the project has subprojects, registers a [KoverGateAggregateReportTask] to merge all
   * submodule gap reports. Otherwise registers a [KoverGateReportTask] that checks Kover coverage for this module.
   */
  override fun apply(project: Project) {
    val extension = project.extensions.create("koverGate", CoverageGateExtension::class.java)

    if (project.parent == null && project.subprojects.isNotEmpty()) {
      applyAsAggregateRoot(project, extension)
    } else {
      applyAsLeaf(project, extension)
    }
  }

  private fun applyAsAggregateRoot(project: Project, extension: CoverageGateExtension) {
    val taskProvider =
      project.tasks.register("koverGateReport", KoverGateAggregateReportTask::class.java) {
        group = "verification"
        description = "Aggregates Kover coverage gap reports from all submodules"
        projectPath.set(project.path)
        prettyPrintJson.set(extension.prettyPrintJson)
        jsonReportFile.set(project.layout.buildDirectory.file("reports/kover/gap-report.json"))
      }

    // Collect submodule tasks using a lazy approach
    taskProvider.configure {
      // Use project.provider to lazily evaluate the tasks
      val subprojectTasks = project.provider {
        project.subprojects.mapNotNull { sub -> sub.tasks.findByName("koverGateReport") }
      }

      // Depend on all koverGateReport tasks found in subprojects
      dependsOn(subprojectTasks)

      // Add the output files from those tasks
      inputJsonFiles.from(
        project.provider {
          project.subprojects.mapNotNull { sub ->
            (sub.tasks.findByName("koverGateGenerateReport") as? KoverGateReportTask)?.jsonReportFile?.asFile?.orNull
          }
        }
      )
    }

    val scopedAggregate =
      project.tasks.register("koverGateScopedReport", KoverGateAggregateReportTask::class.java) {
        group = "verification"
        description = "Aggregates scoped (changed-lines) coverage gap reports from all submodules"
        projectPath.set(project.path)
        prettyPrintJson.set(extension.prettyPrintJson)
        jsonReportFile.set(project.layout.buildDirectory.file("reports/kover/scoped-gap-report.json"))
      }

    scopedAggregate.configure {
      val subprojectTasks = project.provider {
        project.subprojects.mapNotNull { it.tasks.findByName("koverGateScopedReport") }
      }

      dependsOn(subprojectTasks)
      inputJsonFiles.from(
        project.provider<List<java.io.File>> {
          project.subprojects.mapNotNull { sub ->
            (sub.tasks.findByName("koverGateScopedReport") as? KoverGateScopedReportTask)
              ?.scopedReportFile
              ?.asFile
              ?.orNull
          }
        }
      )
    }
  }

  private fun applyAsLeaf(project: Project, extension: CoverageGateExtension) {
    val generateTaskProvider =
      project.tasks.register("koverGateGenerateReport", KoverGateReportTask::class.java) {
        group = "verification"
        description = "Parses Kover XML coverage and writes the gap report JSON"
        projectPath.set(project.path)
        minLineCoverage.set(extension.minLineCoverage)
        minBranchCoverage.set(extension.minBranchCoverage)
        minInstructionCoverage.set(extension.minInstructionCoverage)
        minMethodCoverage.set(extension.minMethodCoverage)
        minClassCoverage.set(extension.minClassCoverage)
        disabledMetrics.set(extension.disabledMetrics)
        prettyPrintJson.set(extension.prettyPrintJson)
        jsonReportFile.set(project.layout.buildDirectory.file("reports/kover/gap-report.json"))
      }

    val gapReportProvider = generateTaskProvider.flatMap { it.jsonReportFile }

    project.tasks.register("koverGateReport", KoverGateCheckTask::class.java) {
      group = "verification"
      description = "Fails the build if the gap report shows any unmet coverage threshold"
      projectPath.set(project.path)
      gapReportFile.set(gapReportProvider)
      gateStampFile.set(project.layout.buildDirectory.file("reports/kover/gate-stamp.txt"))
      dependsOn(generateTaskProvider)
    }

    registerScopedReport(project, extension, gapReportProvider, generateTaskProvider)

    project.afterEvaluate {
      autoDisableJacocoIfNeeded(project)
      if (extension.xmlReportFile.isPresent) {
        generateTaskProvider.configure { xmlReportFile.set(extension.xmlReportFile) }
      } else {
        if (!project.pluginManager.hasPlugin("org.jetbrains.kotlinx.kover")) {
          throw GradleException(
            "KoverGate requires the Kover plugin. " +
              "Apply 'org.jetbrains.kotlinx.kover' before 'com.commonsware.kovergate'."
          )
        }

        val koverTask = findKoverTask(project, extension)

        generateTaskProvider.configure {
          dependsOn(koverTask)
          xmlReportFile.set(project.layout.file(project.provider { koverTask.outputs.files.singleFile }))
        }
      }
    }
  }

  private fun registerScopedReport(
    project: Project,
    extension: CoverageGateExtension,
    gapReportProvider: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>,
    generateTaskProvider: org.gradle.api.tasks.TaskProvider<KoverGateReportTask>,
  ) {
    val scopedTaskProvider =
      project.tasks.register("koverGateScopedReport", KoverGateScopedReportTask::class.java) {
        group = "verification"
        description = "Reports coverage gaps filtered to a scope (default: lines changed on the current branch)"
        projectPath.set(project.path)
        prettyPrintJson.set(extension.prettyPrintJson)
        baseRef.convention(project.providers.gradleProperty("koverGateBase").orElse("main"))
        scope.convention(ScopeType.BRANCH.name.lowercase())
        gapReportFile.set(gapReportProvider)
        scopedReportFile.set(project.layout.buildDirectory.file("reports/kover/scoped-gap-report.json"))
        sourceRoots.from(mainSourceRoots(project))
        dependsOn(generateTaskProvider)
      }

    scopedTaskProvider.configure {
      gitDiff.set(
        project.providers.of(GitDiffValueSource::class.java) {
          parameters.projectDirectory.set(project.layout.projectDirectory)
          parameters.baseRef.set(baseRef)
        }
      )
    }
  }

  private fun mainSourceRoots(project: Project): List<java.io.File> =
    listOf(project.file("src/main/kotlin"), project.file("src/main/java"))

  private fun findKoverTask(project: Project, extension: CoverageGateExtension) =
    extension.xmlReportTask
      .get()
      .takeIf { it.isNotBlank() }
      ?.let { customTaskName ->
        project.tasks.findByName(customTaskName)
          ?: throw GradleException(
            "KoverGate: xmlReportTask '$customTaskName' not found in project '${project.path}'. " +
              "Ensure the task name is correct and the Kover plugin is applied."
          )
      }
      ?: project.tasks.findByName("koverXmlReportJvm")
      ?: project.tasks.findByName("koverXmlReport")
      ?: run {
        val candidates = project.tasks.names.filter { it.startsWith("koverXmlReport") }.sorted()
        val hint =
          if (candidates.isNotEmpty()) " Available koverXmlReport* tasks: ${candidates.joinToString(", ")}." else ""
        throw GradleException(
          "KoverGate: Could not find 'koverXmlReportJvm' or 'koverXmlReport'. " +
            "For Android projects, specify the variant task in the koverGate {} block " +
            "(e.g., xmlReportTask.set(\"koverXmlReportDebug\")). $hint"
        )
      }

  /**
   * Automatically disables the Gradle `jacoco` plugin's instrumentation of [Test] tasks when Kover's `useJacoco` is
   * active. Without this, both the Kover JaCoCo agent and the Gradle jacoco plugin agent attach to the same [Test]
   * task, causing a duplicate class definition crash at runtime.
   *
   * Only applies when all three conditions are met:
   * - The Gradle `jacoco` plugin is applied.
   * - The Kover plugin is applied.
   * - Kover's `useJacoco` property is `true` (i.e., `kover { useJacoco() }` was called).
   *
   * Kover's extension is accessed by name and reflected to read `useJacoco`, so no compile-time or runtime dependency
   * on the Kover plugin JAR is required.
   *
   * @param project the Gradle project to inspect and configure
   */
  private fun autoDisableJacocoIfNeeded(project: Project) {
    if (!project.pluginManager.hasPlugin("jacoco")) return

    if (!isKoverUseJacocoEnabled(project)) return

    project.tasks.withType(Test::class.java).configureEach {
      extensions.getByType(JacocoTaskExtension::class.java).isEnabled = false
    }
    project.logger.lifecycle(
      "KoverGate: disabled Gradle jacoco instrumentation on Test tasks (Kover useJacoco() detected)"
    )
  }

  /**
   * Returns `true` if the Kover plugin is applied and its `useJacoco` property is `true`.
   *
   * Uses reflection to avoid a compile-time or runtime dependency on the Kover plugin JAR — Kover's classloader may be
   * separate from KoverGate's in some Gradle configurations.
   *
   * @param project the Gradle project to inspect
   * @return `true` if Kover is applied and JaCoCo mode is enabled, `false` otherwise
   */
  private fun isKoverUseJacocoEnabled(project: Project): Boolean {
    val koverExt = project.extensions.findByName("kover") ?: return false

    return try {
      val useJacocoProperty = koverExt.javaClass.getMethod("getUseJacoco").invoke(koverExt)
      val getOrElse = useJacocoProperty.javaClass.getMethod("getOrElse", Any::class.java)

      getOrElse.invoke(useJacocoProperty, false) as Boolean
    } catch (_: Exception) {
      false
    }
  }
}
