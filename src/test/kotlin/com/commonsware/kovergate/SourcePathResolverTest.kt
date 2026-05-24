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

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("FunctionNaming")
class SourcePathResolverTest {

  @TempDir lateinit var gitRoot: File

  @Test
  fun `resolves gap to git-relative path under kotlin source root`() {
    val srcRoot = File(gitRoot, "module/src/main/kotlin")
    val pkgDir = File(srcRoot, "com/example/app")
    pkgDir.mkdirs()
    File(pkgDir, "Calculator.kt").writeText("// file")
    val gap = FileGap("Calculator.kt", "com/example/app", listOf(5), emptyList())

    val path = resolveGitRelativePath(gap, listOf(srcRoot), gitRoot)

    assertEquals("module/src/main/kotlin/com/example/app/Calculator.kt", path)
  }

  @Test
  fun `returns null when file does not exist under any root`() {
    val srcRoot = File(gitRoot, "src/main/kotlin")
    srcRoot.mkdirs()
    val gap = FileGap("Missing.kt", "com/example", listOf(1), emptyList())

    val path = resolveGitRelativePath(gap, listOf(srcRoot), gitRoot)

    assertNull(path)
  }

  @Test
  fun `prefers first matching root when multiple roots provided`() {
    val kotlinRoot = File(gitRoot, "src/main/kotlin")
    val javaRoot = File(gitRoot, "src/main/java")
    File(javaRoot, "com/example").mkdirs()
    File(javaRoot, "com/example/Legacy.kt").writeText("// file")
    val gap = FileGap("Legacy.kt", "com/example", listOf(1), emptyList())

    val path = resolveGitRelativePath(gap, listOf(kotlinRoot, javaRoot), gitRoot)

    assertEquals("src/main/java/com/example/Legacy.kt", path)
  }
}
