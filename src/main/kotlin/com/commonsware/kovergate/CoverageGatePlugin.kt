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
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

/** Gradle plugin that configures coverage gate checking against Kover XML reports. */
public class CoverageGatePlugin @Inject constructor(private val listenerRegistry: BuildEventsListenerRegistry) :
  Plugin<Project> {
  /**
   * Applies the plugin, taking one of three roles (see [ProjectRole]). A project that is not a root with subprojects is
   * a leaf: it registers a [KoverGateReportTask] that checks Kover coverage for the module. A root with subprojects is
   * either an aggregate, which only registers a [KoverGateAggregateReportTask] to merge the submodule gap reports, or a
   * hybrid, which is a leaf for its own classes and also registers the aggregates as `koverGateAggregateReport` and
   * `koverGateAggregateScopedReport`. The `koverGateRootMode` Gradle property chooses between them; by default a root
   * is a hybrid when Kover and a Java, Kotlin or Android plugin were applied before this plugin.
   */
  override fun apply(project: Project) {
    registerFreshnessService(project, listenerRegistry)

    val isRootWithSubprojects = project.parent == null && project.subprojects.isNotEmpty()
    val mode =
      if (isRootWithSubprojects) parseRootMode(project.providers.gradleProperty(ROOT_MODE_PROPERTY).orNull)
      else RootMode.AUTO
    val role =
      resolveProjectRole(
        isRootWithSubprojects = isRootWithSubprojects,
        mode = mode,
        hasKover = hasKover(project),
        hasCodePlugin = hasCodePlugin(project),
      )

    when (role) {
      ProjectRole.LEAF ->
        applyAsLeaf(project, project.extensions.create("koverGate", CoverageGateExtension::class.java))
      ProjectRole.AGGREGATE -> {
        val extension = project.extensions.create("koverGate", RootCoverageGateExtension::class.java)

        applyAsAggregateRoot(project, extension.prettyPrintJson, AggregateNames.AGGREGATE_ROOT, includeSelf = false)

        if (mode == RootMode.AUTO) registerMisorderGuard(project)
      }
      ProjectRole.HYBRID -> {
        val extension = project.extensions.create("koverGate", CoverageGateExtension::class.java)

        applyAsLeaf(project, extension)
        applyAsAggregateRoot(project, extension.prettyPrintJson, AggregateNames.HYBRID_ROOT, includeSelf = true)
      }
    }
  }

  /**
   * The task names and output paths for a root project's two aggregate reports.
   *
   * @property fullTaskName name of the full aggregate task
   * @property fullReportPath path of the full aggregate's JSON, relative to the build directory
   * @property scopedTaskName name of the scoped aggregate task
   * @property scopedReportPath path of the scoped aggregate's JSON, relative to the build directory
   */
  private class AggregateNames(
    val fullTaskName: String,
    val fullReportPath: String,
    val scopedTaskName: String,
    val scopedReportPath: String,
  ) {
    companion object {
      /** An aggregate-only root, which owns the leaf task names. */
      val AGGREGATE_ROOT =
        AggregateNames(
          "koverGateReport",
          "reports/kover/gap-report.json",
          "koverGateScopedReport",
          "reports/kover/scoped-gap-report.json",
        )

      /** A hybrid root, whose leaf tasks keep the leaf names. */
      val HYBRID_ROOT =
        AggregateNames(
          "koverGateAggregateReport",
          "reports/kover/aggregate-gap-report.json",
          "koverGateAggregateScopedReport",
          "reports/kover/aggregate-scoped-gap-report.json",
        )
    }
  }

  private fun applyAsAggregateRoot(
    project: Project,
    prettyPrint: Property<Boolean>,
    names: AggregateNames,
    includeSelf: Boolean,
  ) {
    // Subprojects are always leaves, so their lookups use the leaf names. A hybrid root's own leaf tasks are included
    // the same way, which makes its entry in the aggregate module ":".
    val sources = project.provider {
      if (includeSelf) listOf(project) + project.subprojects else project.subprojects.toList()
    }

    val taskProvider =
      project.tasks.register(names.fullTaskName, KoverGateAggregateReportTask::class.java) {
        group = "verification"
        description = "Aggregates Kover coverage gap reports from all submodules"
        projectPath.set(project.path)
        prettyPrintJson.set(prettyPrint)
        jsonReportFile.set(project.layout.buildDirectory.file(names.fullReportPath))
      }

    taskProvider.configure {
      // Depend on all koverGateReport tasks found, lazily evaluated
      dependsOn(project.provider { sources.get().mapNotNull { it.tasks.findByName("koverGateReport") } })

      // Add the output files from those tasks
      inputJsonFiles.from(
        project.provider {
          sources.get().mapNotNull { sub ->
            (sub.tasks.findByName("koverGateGenerateReport") as? KoverGateReportTask)?.jsonReportFile?.asFile?.orNull
          }
        }
      )
    }

    val scopedAggregate =
      project.tasks.register(names.scopedTaskName, KoverGateAggregateReportTask::class.java) {
        group = "verification"
        description = "Aggregates scoped (changed-lines) coverage gap reports from all submodules"
        projectPath.set(project.path)
        prettyPrintJson.set(prettyPrint)
        jsonReportFile.set(project.layout.buildDirectory.file(names.scopedReportPath))
      }

    scopedAggregate.configure {
      dependsOn(project.provider { sources.get().mapNotNull { it.tasks.findByName("koverGateScopedReport") } })
      inputJsonFiles.from(
        project.provider<List<java.io.File>> {
          sources.get().mapNotNull { sub ->
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
    val producerStampFileProvider = project.layout.buildDirectory.file("reports/kover/producer-stamp.txt")
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
        baselineReportFile.set(project.layout.buildDirectory.file("reports/kover/gap-report-baseline.json"))
        buildScripts.from(buildScriptFiles(project))
        producerStampFile.set(producerStampFileProvider)
        failOnContendedReport.set(extension.failOnContendedReport)
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

    registerDeltaTask(project, extension, gapReportProvider, generateTaskProvider)

    val doctorTaskProvider = registerDoctorReport(project, extension)

    project.afterEvaluate {
      autoDisableJacocoIfNeeded(project)
      wireProducerResolution(project, extension, producerStampFileProvider, generateTaskProvider, doctorTaskProvider)
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
        ignoreSpaceChange.convention(
          project.providers.gradleProperty("koverGateIgnoreSpaceChange").map { it.toBoolean() }.orElse(false)
        )
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
          parameters.ignoreSpaceChange.set(ignoreSpaceChange)
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

  private fun registerDeltaTask(
    project: Project,
    extension: CoverageGateExtension,
    gapReportProvider: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>,
    generateTaskProvider: org.gradle.api.tasks.TaskProvider<KoverGateReportTask>,
  ) {
    project.tasks.register("koverGateDelta", KoverGateDeltaTask::class.java) {
      group = "verification"
      description = "Reports how coverage moved since the previous build"
      projectPath.set(project.path)
      prettyPrintJson.set(extension.prettyPrintJson)
      gapReportFile.set(gapReportProvider)
      baselineReportFile.set(generateTaskProvider.flatMap { it.baselineReportFile })
      deltaReportFile.set(project.layout.buildDirectory.file("reports/kover/gap-delta.json"))
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

/**
 * Resolves the producer task for a leaf module's Kover XML in both wiring branches, attaches the producer stamp, and
 * wires the guard-reach level `koverGateDoctor` reports (§6). Split out of [CoverageGatePlugin.applyAsLeaf] because it
 * needs no plugin instance state — only [project] and the providers/extension passed in.
 *
 * A producer named by `xmlReportTask` is never looked up here: Kover registers its Android variant tasks from a nested
 * `afterEvaluate` that runs after this one, so the name is resolved lazily instead (see [wireNamedProducer]).
 *
 * @param project the module the plugin is applied to
 * @param extension the `koverGate {}` extension supplying the XML override and producer-task name
 * @param producerStampFileProvider the producer-stamp file's location
 * @param generateTaskProvider the registered `koverGateGenerateReport` task
 * @param doctorTaskProvider the registered `koverGateDoctor` task
 */
private fun wireProducerResolution(
  project: Project,
  extension: CoverageGateExtension,
  producerStampFileProvider: Provider<RegularFile>,
  generateTaskProvider: org.gradle.api.tasks.TaskProvider<KoverGateReportTask>,
  doctorTaskProvider: org.gradle.api.tasks.TaskProvider<KoverGateDoctorTask>,
) {
  if (extension.xmlReportFile.isPresent) {
    generateTaskProvider.configure { xmlReportFile.set(extension.xmlReportFile) }
    doctorTaskProvider.configure { xmlReportFile.set(extension.xmlReportFile) }

    val customTaskName = extension.xmlReportTask.get()

    if (customTaskName.isNotBlank()) {
      attachProducerStamp(project, customTaskName, extension.xmlReportFile, producerStampFileProvider)
      wireNamedProducer(project, customTaskName, generateTaskProvider, doctorTaskProvider)
    } else {
      doctorTaskProvider.configure { guardReach.set(ContentionGuardReach.PARTIAL) }
    }
  } else {
    wireAutoDetectedKoverProducer(
      project,
      extension,
      producerStampFileProvider,
      generateTaskProvider,
      doctorTaskProvider,
    )
  }
}

/**
 * The auto-wired (no `xmlReportFile` override) branch of [wireProducerResolution]: a producer task is the source of the
 * XML, either the one named by `xmlReportTask` (resolved lazily) or Kover's own `koverXmlReportJvm` / `koverXmlReport`.
 */
private fun wireAutoDetectedKoverProducer(
  project: Project,
  extension: CoverageGateExtension,
  producerStampFileProvider: Provider<RegularFile>,
  generateTaskProvider: org.gradle.api.tasks.TaskProvider<KoverGateReportTask>,
  doctorTaskProvider: org.gradle.api.tasks.TaskProvider<KoverGateDoctorTask>,
) {
  if (!project.pluginManager.hasPlugin("org.jetbrains.kotlinx.kover")) {
    throw GradleException(
      "KoverGate requires the Kover plugin. Apply 'org.jetbrains.kotlinx.kover' before 'com.commonsware.kovergate'."
    )
  }

  val customTaskName = extension.xmlReportTask.get()

  if (customTaskName.isNotBlank()) {
    val koverXml = project.layout.file(project.provider { findTaskOutputFile(project, customTaskName) })

    generateTaskProvider.configure { xmlReportFile.set(koverXml) }
    doctorTaskProvider.configure { xmlReportFile.set(koverXml) }
    attachProducerStamp(project, customTaskName, koverXml, producerStampFileProvider)
    wireNamedProducer(project, customTaskName, generateTaskProvider, doctorTaskProvider)
  } else {
    val koverTask = findKoverTask(project)
    val koverXml = project.layout.file(project.provider { koverTask.outputs.files.singleFile })

    attachProducerStamp(project, koverTask.name, koverXml, producerStampFileProvider)
    generateTaskProvider.configure {
      dependsOn(koverTask)
      xmlReportFile.set(koverXml)
      producerTaskPath.set(koverTask.path)
    }
    doctorTaskProvider.configure {
      xmlReportFile.set(koverXml)
      guardReach.set(ContentionGuardReach.FULL)
    }
  }
}

/**
 * Wires a producer named by `xmlReportTask` without looking the task up during configuration. The task may be
 * registered after this plugin's `afterEvaluate` runs (Kover's Android variant tasks are), so the name is resolved only
 * when the task graph is built: the generate task depends on a provider that throws the not-found message if the name
 * still does not exist by then, and the doctor receives the same problem as text instead of failing. Callers attach the
 * producer stamp, which needs the XML location only they know.
 *
 * @param project the module the plugin is applied to
 * @param taskName the non-blank `xmlReportTask` value
 * @param generateTaskProvider the registered `koverGateGenerateReport` task
 * @param doctorTaskProvider the registered `koverGateDoctor` task
 */
private fun wireNamedProducer(
  project: Project,
  taskName: String,
  generateTaskProvider: org.gradle.api.tasks.TaskProvider<KoverGateReportTask>,
  doctorTaskProvider: org.gradle.api.tasks.TaskProvider<KoverGateDoctorTask>,
) {
  val problemProvider = project.provider {
    if (taskName in project.tasks.names) {
      null
    } else {
      buildXmlReportTaskNotFoundMessage(taskName, project.path, availableKoverXmlReportTasks(project))
    }
  }

  generateTaskProvider.configure {
    dependsOn(
      project.provider {
        project.tasks.findByName(taskName)
          ?: throw GradleException(
            buildXmlReportTaskNotFoundMessage(taskName, project.path, availableKoverXmlReportTasks(project))
          )
      }
    )
    producerTaskPath.set(qualifiedTaskPath(project.path, taskName))
  }
  doctorTaskProvider.configure {
    guardReach.set(ContentionGuardReach.FULL)
    producerProblem.set(problemProvider)
  }
}

/**
 * Returns the names of the project's `koverXmlReport*` tasks, sorted, for the not-found hint.
 *
 * @param project the module to inspect
 * @return the matching task names, possibly empty
 */
private fun availableKoverXmlReportTasks(project: Project): List<String> =
  project.tasks.names.filter { it.startsWith("koverXmlReport") }.sorted()

/**
 * Returns the single file the named task outputs, or `null` when the task does not exist (yet) so callers evaluating
 * the result — such as `koverGateDoctor` — never throw.
 *
 * @param project the module to inspect
 * @param taskName the task to look up at evaluation time
 * @return the task's single output file, or `null` when no such task exists
 */
private fun findTaskOutputFile(project: Project, taskName: String): java.io.File? =
  project.tasks.findByName(taskName)?.outputs?.files?.singleFile

/**
 * Resolves the task that produces the auto-wired Kover XML when `xmlReportTask` is blank: `koverXmlReportJvm` or
 * `koverXmlReport`, both registered when Kover is applied.
 *
 * @param project the module the plugin is applied to
 * @return the resolved producer task
 */
private fun findKoverTask(project: Project) =
  project.tasks.findByName("koverXmlReportJvm")
    ?: project.tasks.findByName("koverXmlReport")
    ?: run {
      val candidates = availableKoverXmlReportTasks(project)
      val hint =
        if (candidates.isNotEmpty()) " Available koverXmlReport* tasks: ${candidates.joinToString(", ")}." else ""
      throw GradleException(
        "KoverGate: Could not find 'koverXmlReportJvm' or 'koverXmlReport'. " +
          "For Android projects, specify the variant task in the koverGate {} block " +
          "(e.g., xmlReportTask.set(\"koverXmlReportDebug\")). $hint"
      )
    }

/**
 * Builds the error message for an `xmlReportTask` that names a task the project does not have, appending the project's
 * available `koverXmlReport*` tasks as a hint when there are any.
 *
 * @param taskName the `xmlReportTask` value that could not be resolved
 * @param projectPath the Gradle project path (e.g., `:app`)
 * @param candidates the sorted names of the project's `koverXmlReport*` tasks, possibly empty
 * @return the complete error message
 */
internal fun buildXmlReportTaskNotFoundMessage(
  taskName: String,
  projectPath: String,
  candidates: List<String>,
): String {
  val hint = if (candidates.isNotEmpty()) " Available koverXmlReport* tasks: ${candidates.joinToString(", ")}." else ""

  return "KoverGate: xmlReportTask '$taskName' not found in project '$projectPath'. " +
    "Ensure the task name is correct and the Kover plugin is applied.$hint"
}

/**
 * Builds a task's fully-qualified path from its project path and name without needing a realized task.
 *
 * @param projectPath the Gradle project path; the root project's path is `:`
 * @param taskName the task name
 * @return the task path, e.g. `:app:koverXmlReportDebug`, or `:koverXmlReportDebug` for the root project
 */
internal fun qualifiedTaskPath(projectPath: String, taskName: String): String =
  if (projectPath == ":") ":$taskName" else "$projectPath:$taskName"

/**
 * Attaches a configuration-cache-safe `doLast` action to whichever task is named [producerName] that stamps
 * [xmlFileProvider]'s identity (build id, mtime, size) to [stampFileProvider] the instant the producer finishes — the
 * zero-window baseline the report-contention guard compares a gate's read-time observation against (§3).
 *
 * The attachment is lazy (`configureEach`), so it also reaches a producer registered after this call, such as Kover's
 * Android variant tasks. The closure captures only [Provider]s (and the freshness-service provider derived from
 * [project] at configuration time), never `project` or a task reference, so it survives the configuration cache.
 *
 * @param project the module the plugin is applied to
 * @param producerName the name of the task that produces the Kover XML the gate reads
 * @param xmlFileProvider the Kover XML report's location
 * @param stampFileProvider the producer-stamp file's location
 */
private fun attachProducerStamp(
  project: Project,
  producerName: String,
  xmlFileProvider: Provider<RegularFile>,
  stampFileProvider: Provider<RegularFile>,
) {
  val serviceProvider = freshnessServiceProvider(project)

  project.tasks.configureEach {
    if (name == producerName) {
      usesService(serviceProvider)
      doLast {
        val xmlFile = xmlFileProvider.get().asFile
        val stamp =
          ProducerStamp(
            buildId = serviceProvider.get().buildId,
            xmlModifiedEpochMs = xmlFile.lastModified(),
            xmlSizeBytes = xmlFile.length(),
          )
        val stampFile = stampFileProvider.get().asFile

        stampFile.parentFile.mkdirs()
        stampFile.writeText(formatStamp(stamp))
      }
    }
  }
}
