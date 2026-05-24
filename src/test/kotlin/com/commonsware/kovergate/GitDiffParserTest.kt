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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("FunctionNaming")
class GitDiffParserTest {

  @Test
  fun `single hunk with explicit count maps changed lines`() {
    val diff =
      """
      diff --git a/src/main/kotlin/com/example/Calculator.kt b/src/main/kotlin/com/example/Calculator.kt
      index 1111111..2222222 100644
      --- a/src/main/kotlin/com/example/Calculator.kt
      +++ b/src/main/kotlin/com/example/Calculator.kt
      @@ -4,0 +5,3 @@ class Calculator {
      +    fun multiply(a: Int, b: Int): Int = a * b
      +    fun divide(a: Int, b: Int): Int = a / b
      +    fun mod(a: Int, b: Int): Int = a % b
      """
        .trimIndent()

    val result = parseUnifiedDiff(diff)

    assertEquals(setOf(5, 6, 7), result["src/main/kotlin/com/example/Calculator.kt"])
  }

  @Test
  fun `hunk without explicit count means single line`() {
    val diff =
      """
      diff --git a/Foo.kt b/Foo.kt
      --- a/Foo.kt
      +++ b/Foo.kt
      @@ -10 +10 @@
      +changed line
      """
        .trimIndent()

    val result = parseUnifiedDiff(diff)

    assertEquals(setOf(10), result["Foo.kt"])
  }

  @Test
  fun `pure deletion hunk contributes no lines`() {
    val diff =
      """
      diff --git a/Foo.kt b/Foo.kt
      --- a/Foo.kt
      +++ b/Foo.kt
      @@ -3,2 +2,0 @@
      """
        .trimIndent()

    val result = parseUnifiedDiff(diff)

    assertTrue(result["Foo.kt"].isNullOrEmpty())
  }

  @Test
  fun `multiple files are tracked separately`() {
    val diff =
      """
      diff --git a/A.kt b/A.kt
      --- a/A.kt
      +++ b/A.kt
      @@ -1,0 +1,1 @@
      +line in A
      diff --git a/B.kt b/B.kt
      --- a/B.kt
      +++ b/B.kt
      @@ -5,0 +6,2 @@
      +line in B
      +another line in B
      """
        .trimIndent()

    val result = parseUnifiedDiff(diff)

    assertEquals(setOf(1), result["A.kt"])
    assertEquals(setOf(6, 7), result["B.kt"])
  }

  @Test
  fun `empty diff yields empty map`() {
    val result = parseUnifiedDiff("")

    assertTrue(result.isEmpty())
  }
}
