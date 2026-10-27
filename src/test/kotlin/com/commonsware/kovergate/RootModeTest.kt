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

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootModeTest {

  @Test
  fun `parseRootMode maps null to AUTO`() {
    assertEquals(RootMode.AUTO, parseRootMode(null))
  }

  @Test
  fun `parseRootMode maps blank to AUTO`() {
    assertEquals(RootMode.AUTO, parseRootMode("  "))
  }

  @Test
  fun `parseRootMode parses each mode in any case`() {
    assertEquals(RootMode.AUTO, parseRootMode("auto"))
    assertEquals(RootMode.AGGREGATE, parseRootMode("Aggregate"))
    assertEquals(RootMode.HYBRID, parseRootMode("HYBRID"))
  }

  @Test
  fun `parseRootMode rejects unknown value naming property and valid values`() {
    val exception = assertThrows(GradleException::class.java) { parseRootMode("bogus") }
    val message = exception.message.orEmpty()

    assertTrue(message.contains(ROOT_MODE_PROPERTY), message)
    assertTrue(message.contains("bogus"), message)
    assertTrue(message.contains("auto"), message)
    assertTrue(message.contains("aggregate"), message)
    assertTrue(message.contains("hybrid"), message)
  }

  @Test
  fun `resolveProjectRole is LEAF for every mode when not a root with subprojects`() {
    RootMode.entries.forEach { mode ->
      assertEquals(
        ProjectRole.LEAF,
        resolveProjectRole(
          isRootWithSubprojects = false,
          mode = mode,
          hasKover = true,
          hasCodePlugin = true,
        ),
        mode.name,
      )
    }
  }

  @Test
  fun `resolveProjectRole auto with Kover and code plugin is HYBRID`() {
    assertEquals(
      ProjectRole.HYBRID,
      resolveProjectRole(true, RootMode.AUTO, hasKover = true, hasCodePlugin = true),
    )
  }

  @Test
  fun `resolveProjectRole auto with Kover only is AGGREGATE`() {
    assertEquals(
      ProjectRole.AGGREGATE,
      resolveProjectRole(true, RootMode.AUTO, hasKover = true, hasCodePlugin = false),
    )
  }

  @Test
  fun `resolveProjectRole auto with code plugin only is AGGREGATE`() {
    assertEquals(
      ProjectRole.AGGREGATE,
      resolveProjectRole(true, RootMode.AUTO, hasKover = false, hasCodePlugin = true),
    )
  }

  @Test
  fun `resolveProjectRole auto with neither is AGGREGATE`() {
    assertEquals(
      ProjectRole.AGGREGATE,
      resolveProjectRole(true, RootMode.AUTO, hasKover = false, hasCodePlugin = false),
    )
  }

  @Test
  fun `resolveProjectRole explicit aggregate with both plugins is AGGREGATE`() {
    assertEquals(
      ProjectRole.AGGREGATE,
      resolveProjectRole(true, RootMode.AGGREGATE, hasKover = true, hasCodePlugin = true),
    )
  }

  @Test
  fun `resolveProjectRole explicit hybrid with neither plugin is HYBRID`() {
    assertEquals(
      ProjectRole.HYBRID,
      resolveProjectRole(true, RootMode.HYBRID, hasKover = false, hasCodePlugin = false),
    )
  }
}
