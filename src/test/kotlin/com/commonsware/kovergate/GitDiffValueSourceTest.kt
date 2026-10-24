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
import org.junit.jupiter.api.Test

@Suppress("FunctionNaming")
class GitDiffValueSourceTest {

  @Test
  fun `diff args omit the whitespace flag by default`() {
    val args = buildDiffArgs(mergeBase = "abc123", ignoreSpaceChange = false)

    assertEquals(listOf("diff", "--unified=0", "abc123"), args)
  }

  @Test
  fun `diff args add ignore-space-change before the merge base when enabled`() {
    val args = buildDiffArgs(mergeBase = "abc123", ignoreSpaceChange = true)

    assertEquals(listOf("diff", "--unified=0", "--ignore-space-change", "abc123"), args)
  }
}
