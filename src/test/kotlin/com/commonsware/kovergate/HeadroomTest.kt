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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeadroomTest {
  @Test
  fun `headroom is negative 20 for the worked example, not the naive negative 25`() {
    assertEquals(-20, computeHeadroom(covered = 8274, total = 8644, threshold = 96))
  }

  @Test
  fun `headroom is zero exactly at the boundary`() {
    assertEquals(0, computeHeadroom(covered = 180, total = 200, threshold = 90))
  }

  @Test
  fun `headroom is negative one when one covered unit below the boundary`() {
    assertEquals(-1, computeHeadroom(covered = 179, total = 200, threshold = 90))
  }

  @Test
  fun `headroom is zero when half-up rounding lands exactly on the threshold`() {
    assertEquals(0, computeHeadroom(covered = 199, total = 2000, threshold = 10))
  }

  @Test
  fun `headroom is null when total is zero`() {
    assertNull(computeHeadroom(covered = 0, total = 0, threshold = 90))
  }

  @Test
  fun `requiredCoveredFor does not overflow Int for large totals`() {
    // total * (20 * threshold - 1) must not overflow Int arithmetic; threshold = 100 is the worst case
    val total = 2_000_000

    assertTrue(requiredCoveredFor(total, 100) in 0..total)
  }

  @Test
  fun `headroom does not overflow for a large passing total`() {
    val total = 2_000_000

    assertTrue(computeHeadroom(covered = total, total = total, threshold = 100)!! >= 0)
  }
}
