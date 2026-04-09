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

/** Gradle plugin that configures coverage gate checking against Kover XML reports. */
public class CoverageGatePlugin : Plugin<Project> {
  /** Configures the koverGate extension and koverGateReport task for the project. */
  override fun apply(project: Project) {
    val extension = project.extensions.create("koverGate", CoverageGateExtension::class.java)

    val taskProvider =
      project.tasks.register("koverGateReport", KoverGateReportTask::class.java) {
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

    project.afterEvaluate {
      if (!project.pluginManager.hasPlugin("org.jetbrains.kotlinx.kover")) {
        throw GradleException(
          "KoverGate requires the Kover plugin. " +
            "Apply 'org.jetbrains.kotlinx.kover' before 'com.commonsware.kovergate'."
        )
      }

      val customTaskName = extension.xmlReportTask.get().takeIf { it.isNotBlank() }
      val koverTask =
        if (customTaskName != null) {
          project.tasks.findByName(customTaskName)
            ?: throw GradleException(
              "KoverGate: xmlReportTask '$customTaskName' not found in project '${project.path}'. " +
                "Ensure the task name is correct and the Kover plugin is applied."
            )
        } else {
          project.tasks.findByName("koverXmlReportJvm")
            ?: project.tasks.findByName("koverXmlReport")
            ?: run {
              val candidates = project.tasks.names.filter { it.startsWith("koverXmlReport") }.sorted()
              val hint =
                if (candidates.isNotEmpty()) " Available koverXmlReport* tasks: ${candidates.joinToString(", ")}."
                else ""
              throw GradleException(
                "KoverGate: Could not find 'koverXmlReportJvm' or 'koverXmlReport'. " +
                  "For Android projects, specify the variant task in the koverGate {} block " +
                  "(e.g., xmlReportTask.set(\"koverXmlReportDebug\")). $hint"
              )
            }
        }

      taskProvider.configure {
        dependsOn(koverTask)
        xmlReportFile.set(project.layout.file(project.provider { koverTask.outputs.files.singleFile }))
      }
    }
  }
}
