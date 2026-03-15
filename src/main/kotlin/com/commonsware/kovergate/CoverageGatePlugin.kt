package com.commonsware.kovergate

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class CoverageGatePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create("koverGate", CoverageGateExtension::class.java)

    val taskProvider =
      project.tasks.register("koverGapReport", KoverGapReportTask::class.java) {
        group = "verification"
        description = "Checks Kover coverage against configured thresholds and reports gaps"
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

    // Wire dependency and input file from Kover's XML report task.
    // Use whenTaskAdded so we see lazily-registered Kover tasks before they are
    // realized.
    // Prefer koverXmlReportJvm (JVM-specific) over koverXmlReport (all-code
    // aggregate) to
    // avoid double-wiring on multi-platform projects where both tasks exist.
    project.tasks.whenTaskAdded {
      if (name == "koverXmlReportJvm" || name == "koverXmlReport") {
        val koverTask = this
        taskProvider.configure {
          // Only wire the first match (koverXmlReportJvm wins if both register)
          if (!xmlReportFile.isPresent) {
            dependsOn(koverTask)
            xmlReportFile.set(project.layout.file(project.provider { koverTask.outputs.files.singleFile }))
          }
        }
      }
    }

    project.afterEvaluate {
      if (!project.pluginManager.hasPlugin("org.jetbrains.kotlinx.kover")) {
        throw GradleException(
          "KoverGate requires the Kover plugin. " +
            "Apply 'org.jetbrains.kotlinx.kover' before 'com.commonsware.kovergate'."
        )
      }
    }
  }
}
