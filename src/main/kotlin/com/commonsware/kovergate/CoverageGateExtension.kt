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
}
