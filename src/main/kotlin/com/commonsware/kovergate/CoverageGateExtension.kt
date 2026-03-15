package com.commonsware.kovergate

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

abstract class CoverageGateExtension @Inject constructor(objects: ObjectFactory) {
  companion object {
    const val FULL_COVERAGE = 100
  }

  val minLineCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)
  val minBranchCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)
  val minInstructionCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)
  val minMethodCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)
  val minClassCoverage: Property<Int> = objects.property(Int::class.java).convention(FULL_COVERAGE)
  val disabledMetrics: SetProperty<CoverageMetric> =
    objects.setProperty(CoverageMetric::class.java).convention(emptySet())

  /** Whether to pretty-print the JSON gap report output. Defaults to `false`. */
  val prettyPrintJson: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
}
