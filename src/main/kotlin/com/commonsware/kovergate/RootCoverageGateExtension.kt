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

/**
 * DSL extension for the `koverGate {}` block in an aggregate-only root project's `build.gradle.kts`.
 *
 * Aggregate-only roots (root projects that have subprojects but no code of their own, as determined by the
 * `koverGateRootMode` property or by detection) only expose [prettyPrintJson] — coverage thresholds are enforced per
 * leaf module, never at an aggregate-only root. A root that is also a library gets [CoverageGateExtension] instead.
 * Registering a separate, minimal extension type makes that boundary a compile-time DSL guarantee: using threshold
 * properties such as `minLineCoverage` inside `koverGate {}` on a root project produces an "unresolved reference" error
 * at build-script compilation time.
 */
public abstract class RootCoverageGateExtension @Inject constructor(objects: ObjectFactory) {
  /** Whether to pretty-print the aggregate JSON output. Defaults to `false`. */
  public val prettyPrintJson: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
}
