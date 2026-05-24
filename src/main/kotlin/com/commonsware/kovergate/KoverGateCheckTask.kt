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

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that reads a gap report JSON file and fails the build if coverage did not pass.
 *
 * This is the gating half of the split `koverGateReport` pipeline. `koverGateGenerateReport` ([KoverGateReportTask])
 * always runs first and writes the JSON; this task reads it and throws if [GapReport.passed] is `false`.
 *
 * A [gateStampFile] is written only on success, which allows Gradle to track UP-TO-DATE state correctly: a previously
 * failing run leaves no stamp, so the task re-runs on the next invocation even when inputs are unchanged.
 */
@CacheableTask
public abstract class KoverGateCheckTask : DefaultTask() {
  /** The gap-report JSON file produced by [KoverGateReportTask]. */
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) public abstract val gapReportFile: RegularFileProperty

  /**
   * Gradle project path (e.g., `:app`), set by the plugin from [org.gradle.api.Project.getPath]. Used in the failure
   * message.
   */
  @get:Internal public abstract val projectPath: Property<String>

  /**
   * Sentinel file written on successful gate pass.
   *
   * Defaults to `build/reports/kover/gate-stamp.txt`. Only written when [GapReport.passed] is `true`, so a failing run
   * leaves the file absent and Gradle will not mark the task as UP-TO-DATE on the next invocation.
   */
  @get:OutputFile public abstract val gateStampFile: RegularFileProperty

  /** Reads the gap report and throws [GradleException] if coverage did not pass. */
  @TaskAction
  internal fun check() {
    val report = Json.decodeFromString<GapReport>(gapReportFile.get().asFile.readText())

    if (!report.passed) {
      throw GradleException(
        "Coverage gate FAILED for ${projectPath.get()}. " +
          "See gap report at ${gapReportFile.get().asFile.absolutePath}"
      )
    }

    val stampFile = gateStampFile.get().asFile

    stampFile.parentFile.mkdirs()
    stampFile.writeText("passed")
  }
}
