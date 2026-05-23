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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * DSL extension for the `koverGate {}` block in a module's `build.gradle.kts`. Configures the coverage thresholds
 * checked by the `koverGateReport` task. All thresholds default to `100` (full coverage required). Lower a threshold or
 * disable a metric entirely for modules where 100% is not the right target.
 */
public abstract class CoverageGateExtension @Inject constructor(objects: ObjectFactory) {
  /** Constant representing full coverage threshold. */
  public companion object {
    public const val FULL_COVERAGE: Int = 100
  }

  /** Minimum required line coverage percentage. Defaults to `100`. */
  public val minLineCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Minimum required branch coverage percentage. Defaults to `100`. */
  public val minBranchCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Minimum required instruction coverage percentage. Defaults to `100`. */
  public val minInstructionCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Minimum required method coverage percentage. Defaults to `100`. */
  public val minMethodCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Minimum required class coverage percentage. Defaults to `100`. */
  public val minClassCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Metrics to exclude from coverage validation. Defaults to empty set. */
  public val disabledMetrics: SetProperty<CoverageMetric> =
    objects.setProperty(CoverageMetric::class.java).convention(emptySet())

  /** Whether to pretty-print the JSON gap report output. Defaults to `false`. */
  public val prettyPrintJson: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

  /**
   * Name of the Kover XML report task to wire to `koverGateReport`. When blank (the default), the plugin auto-detects
   * `koverXmlReportJvm` (JVM projects) or `koverXmlReport` (multiplatform aggregate).
   *
   * Set this for Android projects where Kover generates variant-specific tasks:
   * ```
   * koverGate {
   *     xmlReportTask.set("koverXmlReportDebug")
   * }
   * ```
   */
  public val xmlReportTask: Property<String> = objects.property(String::class.java).convention("")

  /**
   * Explicit path to a JaCoCo XML report for `koverGateReport` to consume, bypassing Kover task auto-detection.
   *
   * When set, KoverGate reads coverage from this file instead of locating a `koverXmlReport*` task, and the Kover
   * plugin becomes optional — it need not be applied at all. Use this to gate on a report produced outside Kover, such
   * as a `JacocoReport` task that merges unit-test and Android instrumented-test execution data into one report.
   *
   * Wire it from the producing task so Gradle infers the task dependency automatically. Use `map {}` on the
   * `TaskProvider` rather than `flatMap { it.reports.xml.outputLocation }` — the latter triggers a Gradle 9.5
   * output-property validation error on `DefaultSingleFileReport`:
   * ```
   * val mergedCoverage = tasks.register<JacocoReport>("mergedCoverage") { ... }
   * koverGate {
   *     xmlReportFile.set(
   *         mergedCoverage.map { layout.buildDirectory.file("reports/jacoco/mergedCoverage/mergedCoverage.xml").get() }
   *     )
   * }
   * ```
   *
   * When left unset (the default), KoverGate falls back to auto-detecting `koverXmlReportJvm` / `koverXmlReport` (or
   * the task named by [xmlReportTask]) and requires the Kover plugin to be applied.
   */
  public val xmlReportFile: RegularFileProperty = objects.fileProperty()
}
