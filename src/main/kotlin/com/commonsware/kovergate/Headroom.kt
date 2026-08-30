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

private const val PERCENT_MULTIPLIER = 20
private const val ROUNDING_OFFSET = 1
private const val DIVISOR = 2000L

/**
 * Smallest covered count that would satisfy [threshold] for a metric with [total] instrumented units.
 *
 * Inverts the half-up rounding that [computePercent] applies before comparing against [threshold]
 * (`KoverGateReportTask.kt`): `percent >= threshold` iff `round(1000 * covered / total) >= 10 * threshold`, which —
 * since `roundToInt` rounds half-up for non-negative values — holds iff `1000 * covered / total >= 10 * threshold -
 * 0.5`, i.e. `2000 * covered >= total * (20 * threshold - 1)`. Computed with `Long` intermediates to avoid overflowing
 * `Int` for large totals.
 *
 * @param total total number of instrumented units
 * @param threshold the minimum required coverage percentage
 * @return the smallest covered count that would satisfy [threshold]
 */
internal fun requiredCoveredFor(total: Int, threshold: Int): Int {
  val numerator = total.toLong() * (PERCENT_MULTIPLIER * threshold - ROUNDING_OFFSET)

  return ((numerator + DIVISOR - 1) / DIVISOR).toInt()
}

/**
 * Covered units separating this metric from its threshold: positive when passing with room to spare, negative when
 * short by that many, `null` when [total] is zero (nothing instrumented, so no meaningful distance to a threshold).
 *
 * @param covered the number of covered units (lines, branches, etc.)
 * @param total total number of instrumented units
 * @param threshold the minimum required coverage percentage
 * @return the signed headroom, or `null` when [total] is zero
 */
internal fun computeHeadroom(covered: Int, total: Int, threshold: Int): Int? {
  if (total == 0) return null

  return covered - requiredCoveredFor(total, threshold)
}
