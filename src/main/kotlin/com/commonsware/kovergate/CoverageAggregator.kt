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
 * Recomputes a [ParseResult]'s module-level coverage from the classes surviving a [ClassNameExclusionMatcher], so
 * koverGate's verdict reflects its own parse-time exclusions regardless of whether Kover itself filtered the XML it
 * read.
 */
internal object CoverageAggregator {

  /**
   * Filters [raw]'s classes by [matcher], recomputes [ParseResult.moduleCounters] from the survivors, and drops any
   * [ParseResult.files] entry whose owning classes were all excluded. A [matcher] with no patterns returns [raw]
   * unchanged.
   *
   * @param raw the parse result to filter, as read directly from the Kover XML
   * @param matcher the exclusion matcher to apply against each class's fully-qualified name
   * @return a [ParseResult] whose counters, files, and classes reflect only the surviving classes
   */
  internal fun aggregate(raw: ParseResult, matcher: ClassNameExclusionMatcher): ParseResult {
    if (matcher.isEmpty) return raw

    val survivingClasses = raw.classes.filterNot { matcher.isExcluded(it.fqcn) }

    return raw.copy(
      moduleCounters = recomputeCounters(survivingClasses),
      files = survivingFiles(raw, matcher),
      classes = survivingClasses,
    )
  }

  private fun recomputeCounters(classes: List<ClassCoverage>): Map<CoverageMetric, Counter> =
    CoverageMetric.entries
      .mapNotNull { metric ->
        val counters = classes.mapNotNull { it.counters[metric] }

        if (counters.isEmpty()) null
        else
          metric to
            counters.fold(Counter(missed = 0, covered = 0)) { acc, counter ->
              Counter(missed = acc.missed + counter.missed, covered = acc.covered + counter.covered)
            }
      }
      .toMap()

  private fun survivingFiles(raw: ParseResult, matcher: ClassNameExclusionMatcher): List<FileCoverage> {
    val classesByFile = raw.classes.groupBy { it.packageName to it.sourcefilename }

    return raw.files.filter { file ->
      val owningClasses = classesByFile[file.packageName to file.name]

      owningClasses.isNullOrEmpty() || owningClasses.any { !matcher.isExcluded(it.fqcn) }
    }
  }
}
