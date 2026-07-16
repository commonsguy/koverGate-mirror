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

/** Regex metacharacters that must be escaped verbatim when translating a Kover-style wildcard pattern to a regex. */
private val REGEX_METACHARACTERS = "<([{\\^-=$!|]})+.>".toSet()

/**
 * Converts a Kover-style wildcard [pattern] into an equivalent regex fragment, mirroring
 * `kotlinx.kover.features.jvm.impl.String.wildcardsToRegex()` (`kover-features-jvm:0.9.8`):
 *
 * - `*` becomes `.*` (any characters, including dots)
 * - `?` becomes `.` (exactly one arbitrary character)
 * - `#` becomes `[^.]*` (any characters except dots, i.e. within one package segment)
 * - any other regex metacharacter is escaped so it matches itself literally
 *
 * @param pattern the Kover-style wildcard pattern to convert
 * @return the equivalent regex fragment (not yet anchored to a full-string match)
 */
internal fun koverWildcardToRegex(pattern: String): String {
  val builder = StringBuilder(pattern.length * 2)

  pattern.forEach { char ->
    when (char) {
      in REGEX_METACHARACTERS -> builder.append('\\').append(char)
      '*' -> builder.append(".*")
      '?' -> builder.append('.')
      '#' -> builder.append("[^.]*")
      else -> builder.append(char)
    }
  }

  return builder.toString()
}

/**
 * Matches fully-qualified dotted class names against a set of Kover-style wildcard [patterns], answering whether a
 * given class should be excluded from koverGate's parse-time coverage recomputation. Semantics mirror Kover's own
 * `*`/`?`/`#` wildcard-to-regex translation exactly (see [koverWildcardToRegex]), and matching is against the whole
 * fully-qualified name (no partial matches).
 *
 * @param patterns the set of Kover-style wildcard patterns to exclude by; an empty set excludes nothing
 */
internal class ClassNameExclusionMatcher(patterns: Set<String>) {
  private val regexes = patterns.map { Regex(koverWildcardToRegex(it)) }

  /** `true` when this matcher has no patterns and therefore excludes nothing. */
  val isEmpty: Boolean = patterns.isEmpty()

  /**
   * Determines whether the class identified by [fqcn] matches any of this matcher's patterns.
   *
   * @param fqcn the fully-qualified dotted class name (e.g. `com.example.Widget`)
   * @return `true` if [fqcn] should be excluded
   */
  fun isExcluded(fqcn: String): Boolean = regexes.any { it.matches(fqcn) }
}
