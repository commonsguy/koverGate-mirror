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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassNameExclusionMatcherTest {
  @Test
  fun `star wildcard crosses dots`() {
    val matcher = ClassNameExclusionMatcher(setOf("com.example.*"))

    assertTrue(matcher.isExcluded("com.example.Widget"))
    assertTrue(matcher.isExcluded("com.example.sub.Qux"))
    assertFalse(matcher.isExcluded("com.other.Widget"))
  }

  @Test
  fun `question mark matches exactly one arbitrary character`() {
    val matcher = ClassNameExclusionMatcher(setOf("com.example.Widget?"))

    assertTrue(matcher.isExcluded("com.example.Widget1"))
    assertFalse(matcher.isExcluded("com.example.Widget"))
    assertFalse(matcher.isExcluded("com.example.Widget12"))
  }

  @Test
  fun `hash wildcard stays within one package segment`() {
    val matcher = ClassNameExclusionMatcher(setOf("com.example.#"))

    assertTrue(matcher.isExcluded("com.example.Widget"))
    assertFalse(matcher.isExcluded("com.example.sub.Qux"))
  }

  @Test
  fun `regex metacharacters in patterns are escaped literally`() {
    val matcher = ClassNameExclusionMatcher(setOf("com.example.Wid+get(1)"))

    assertTrue(matcher.isExcluded("com.example.Wid+get(1)"))
    assertFalse(matcher.isExcluded("com.example.Widget1"))
  }

  @Test
  fun `match is full string, not partial`() {
    val matcher = ClassNameExclusionMatcher(setOf("com.example.Widget"))

    assertFalse(matcher.isExcluded("com.example.WidgetExtra"))
    assertFalse(matcher.isExcluded("prefix.com.example.Widget"))
    assertTrue(matcher.isExcluded("com.example.Widget"))
  }

  @Test
  fun `empty pattern set excludes nothing and reports empty`() {
    val matcher = ClassNameExclusionMatcher(emptySet())

    assertTrue(matcher.isEmpty)
    assertFalse(matcher.isExcluded("com.example.Widget"))
    assertFalse(matcher.isExcluded(""))
  }

  @Test
  fun `koverWildcardToRegex converts wildcards and escapes metacharacters`() {
    assertEquals("""com\.example\..*""", koverWildcardToRegex("com.example.*"))
    assertEquals("""com\.example\..""", koverWildcardToRegex("com.example.?"))
    assertEquals("""com\.example\.[^.]*""", koverWildcardToRegex("com.example.#"))
    assertEquals("""a\+b\(c\)""", koverWildcardToRegex("a+b(c)"))
  }
}
