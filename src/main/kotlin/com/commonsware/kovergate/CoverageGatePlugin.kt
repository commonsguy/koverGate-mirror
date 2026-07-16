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

import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

/** Gradle plugin that configures coverage gate checking against Kover XML reports. */
public class CoverageGatePlugin @Inject constructor(private val listenerRegistry: BuildEventsListenerRegistry) :
  Plugin<Project> {
  /**
   * Applies the plugin. If the project has subprojects, registers a [KoverGateAggregateReportTask] to merge all
   * submodule gap reports. Otherwise registers a [KoverGateReportTask] that checks Kover coverage for this module.
   */
  override fun apply(project: Project) {
    registerFreshnessService(project, listenerRegistry)

    if (project.parent == null && project.subprojects.isNotEmpty()) {
      val extension = project.extensions.create("koverGate", RootCoverageGateExtension::class.java)
      applyAsAggregateRoot(project, extension)
    } else {
      val extension = project.extensions.create("koverGate", CoverageGateExtension::class.java)
      applyAsLeaf(project, extension)
    }
  }

  private fun applyAsAggregateRoot(project: Project, extension: RootCoverageGateExtension) {
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
        excludePatterns.set(extension.excludePatterns)
        prettyPrintJson.set(extension.prettyPrintJson)
        jsonReportFile.set(project.layout.buildDirectory.file("reports/kover/gap-report.json"))
        buildScripts.from(buildScriptFiles(project))
        // Always re-run this cheap (XML→JSON) step so the freshness check is re-evaluated every build and a
        // stale gap report can never be served from an up-to-date or cached result.
        outputs.upToDateWhen { false }
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

    registerBumpTask(project, gapReportProvider, generateTaskProvider)

    val doctorTaskProvider = registerDoctorReport(project, extension)

    project.afterEvaluate {
      autoDisableJacocoIfNeeded(project)
      if (extension.xmlReportFile.isPresent) {
        generateTaskProvider.configure { xmlReportFile.set(extension.xmlReportFile) }
        doctorTaskProvider.configure { xmlReportFile.set(extension.xmlReportFile) }
      } else {
        if (!project.pluginManager.hasPlugin("org.jetbrains.kotlinx.kover")) {
          throw GradleException(
            "KoverGate requires the Kover plugin. " +
              "Apply 'org.jetbrains.kotlinx.kover' before 'com.commonsware.kovergate'."
          )
        }

        val koverTask = findKoverTask(project, extension)
        val koverXml = project.layout.file(project.provider { koverTask.outputs.files.singleFile })

        generateTaskProvider.configure {
          dependsOn(koverTask)
          xmlReportFile.set(koverXml)
        }
        doctorTaskProvider.configure { xmlReportFile.set(koverXml) }
      }

      applyCoverageContributions(project, extension)
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
        sourceRoots.from(listOf(project.file("src/main/kotlin"), project.file("src/main/java")))
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

  private fun registerBumpTask(
    project: Project,
    gapReportProvider: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>,
    generateTaskProvider: org.gradle.api.tasks.TaskProvider<KoverGateReportTask>,
  ) {
    project.tasks.register("koverGateBump", KoverGateBumpTask::class.java) {
      group = "verification"
      description = "Suggests updated koverGate {} thresholds from the current coverage report (ratchets up by default)"
      projectPath.set(project.path)
      gapReportFile.set(gapReportProvider)
      margin.convention("0")
      allowLower.convention(false)
      dependsOn(generateTaskProvider)
    }
  }

  /**
   * Registers the `koverGateDoctor` diagnostic task for a leaf module. The task's `xmlReportFile` is wired separately
   * in the plugin's `afterEvaluate` block, alongside the generate task, because the XML source (an explicit override
   * versus the auto-detected Kover task) is only known after evaluation.
   *
   * @param project the module the plugin is applied to
   * @param extension the `koverGate {}` extension supplying thresholds, disabled metrics, and recorded contributions
   * @return the provider for the registered [KoverGateDoctorTask]
   */
  private fun registerDoctorReport(
    project: Project,
    extension: CoverageGateExtension,
  ): org.gradle.api.tasks.TaskProvider<KoverGateDoctorTask> =
    project.tasks.register("koverGateDoctor", KoverGateDoctorTask::class.java) {
      group = "verification"
      description =
        "Prints coverage thresholds, the Kover XML it will read, that XML's freshness, and the resolved excludes"
      projectPath.set(project.path)
      minLineCoverage.set(extension.minLineCoverage)
      minBranchCoverage.set(extension.minBranchCoverage)
      minInstructionCoverage.set(extension.minInstructionCoverage)
      minMethodCoverage.set(extension.minMethodCoverage)
      minClassCoverage.set(extension.minClassCoverage)
      disabledMetrics.set(extension.disabledMetrics)
      buildScripts.from(buildScriptFiles(project))
      resolvedExcludes.set(extension.excludePatterns.map { it.toList() })
    }

  /**
   * Returns the build scripts whose modification times the generate and doctor tasks compare against the Kover XML to
   * detect a stale report: this module's build script, the root project's build script, the root settings file, and the
   * version catalog. Only files that currently exist are returned.
   *
   * @param project the module the plugin is applied to
   * @return the existing build-script files relevant to this module's coverage configuration
   */
  private fun buildScriptFiles(project: Project): List<java.io.File> {
    val rootDir = project.rootProject.projectDir
    val candidates =
      listOf(
        project.buildFile,
        project.rootProject.buildFile,
        java.io.File(rootDir, "settings.gradle.kts"),
        java.io.File(rootDir, "settings.gradle"),
        java.io.File(rootDir, "gradle/libs.versions.toml"),
      )

    return candidates.filter { it.exists() }
  }

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
