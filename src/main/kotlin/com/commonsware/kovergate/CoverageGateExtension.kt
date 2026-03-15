package com.commonsware.kovergate

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject
import org.gradle.api.model.ObjectFactory

abstract class CoverageGateExtension @Inject constructor(objects: ObjectFactory) {
    val minLineCoverage: Property<Int> =
        objects.property(Int::class.java).convention(100)
    val minBranchCoverage: Property<Int> =
        objects.property(Int::class.java).convention(100)
    val minInstructionCoverage: Property<Int> =
        objects.property(Int::class.java).convention(100)
    val minMethodCoverage: Property<Int> =
        objects.property(Int::class.java).convention(100)
    val minClassCoverage: Property<Int> =
        objects.property(Int::class.java).convention(100)
    val disabledMetrics: SetProperty<CoverageMetric> =
        objects.setProperty(CoverageMetric::class.java).convention(emptySet())
}
