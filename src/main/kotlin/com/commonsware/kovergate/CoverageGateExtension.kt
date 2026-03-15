package com.commonsware.kovergate

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

abstract class CoverageGateExtension @Inject constructor(objects: ObjectFactory) {
  /** Constant representing full coverage threshold. */
  companion object {
    const val FULL_COVERAGE = 100
  }

  /** Minimum required line coverage percentage. Defaults to `100`. */
  val minLineCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Minimum required branch coverage percentage. Defaults to `100`. */
  val minBranchCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Minimum required instruction coverage percentage. Defaults to `100`. */
  val minInstructionCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Minimum required method coverage percentage. Defaults to `100`. */
  val minMethodCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Minimum required class coverage percentage. Defaults to `100`. */
  val minClassCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)

  /** Metrics to exclude from coverage validation. Defaults to empty set. */
  val disabledMetrics: SetProperty<CoverageMetric> =
    objects.setProperty(CoverageMetric::class.java).convention(emptySet())

  /** Whether to pretty-print the JSON gap report output. Defaults to `false`. */
  val prettyPrintJson: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
}
