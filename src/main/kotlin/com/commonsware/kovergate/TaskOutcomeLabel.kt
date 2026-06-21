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

/**
 * The outcome labels the freshness signal records for a finished task. These mirror the Gradle task-result states that
 * [buildReuseWarnings] distinguishes, kept as plain strings so the decision logic stays Gradle-free and unit-testable.
 */
internal object TaskOutcomeLabel {
  /** The task executed its action this build. */
  const val EXECUTED = "EXECUTED"
  /** The task ran and failed this build. */
  const val FAILED = "FAILED"
  /** Gradle considered the task up-to-date and skipped its action. */
  const val UP_TO_DATE = "UP_TO_DATE"
  /** Gradle served the task's outputs from the build cache. */
  const val FROM_CACHE = "FROM_CACHE"
  /** The task was skipped (e.g. no source). */
  const val SKIPPED = "SKIPPED"
  /** The outcome could not be classified. */
  const val UNKNOWN = "UNKNOWN"
}
