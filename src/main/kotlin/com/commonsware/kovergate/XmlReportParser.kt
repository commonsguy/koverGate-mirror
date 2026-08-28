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
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal data class Counter(val missed: Int, val covered: Int)

/**
 * Per-line branch coverage detail.
 *
 * @property line the 1-based source line number containing the branches
 * @property missed the number of branches on this line with no coverage
 * @property total the total number of branches on this line (missed plus covered)
 * @property fullyInstructionCovered true when the line's instructions are all covered (`mi == 0`), so the missed branch
 *   arm is the only gap — frequently a synthetic or unreachable arm rather than untested logic
 */
internal data class BranchLine(
  val line: Int,
  val missed: Int,
  val total: Int,
  val fullyInstructionCovered: Boolean = false,
)

/**
 * Per-file coverage detail, parsed from a `<sourcefile>` element.
 *
 * @property name the source file's simple name, e.g. `Widget.kt`
 * @property packageName the slash-separated package name
 * @property uncoveredLines line numbers with zero covered instructions (`ci == 0`) — never executed
 * @property branchLines per-line branch coverage detail
 * @property missedBranches the file's total missed branches, from the file-level BRANCH counter
 * @property totalBranches the file's total branches (missed plus covered)
 * @property partiallyCoveredLines line numbers with both covered and missed instructions
 * @property missedInstructionsCold missed instructions (`mi`) summed over [uncoveredLines] — instructions on lines that
 *   never executed
 * @property missedInstructionsResidual missed instructions (`mi`) summed over [partiallyCoveredLines] — instructions on
 *   lines that did execute, frequently synthetic duplicate bytecode
 */
internal data class FileCoverage(
  val name: String,
  val packageName: String,
  val uncoveredLines: List<Int>,
  val branchLines: List<BranchLine> = emptyList(),
  val missedBranches: Int = 0,
  val totalBranches: Int = 0,
  val partiallyCoveredLines: List<Int> = emptyList(),
  val missedInstructionsCold: Int = 0,
  val missedInstructionsResidual: Int = 0,
)

/**
 * Per-class coverage, parsed from a `<class>` element's own (non-nested) counters.
 *
 * @property fqcn the class's fully-qualified dotted name, e.g. `com.example.Widget`
 * @property sourcefilename the source file name the class was compiled from, e.g. `Widget.kt`
 * @property packageName the slash-separated package name, matching [FileCoverage.packageName]
 * @property counters this class's own coverage counters, keyed by metric
 */
internal data class ClassCoverage(
  val fqcn: String,
  val sourcefilename: String,
  val packageName: String,
  val counters: Map<CoverageMetric, Counter>,
)

internal data class ParseResult(
  val moduleCounters: Map<CoverageMetric, Counter>,
  val files: List<FileCoverage>,
  val classes: List<ClassCoverage> = emptyList(),
)

internal object XmlReportParser {
  internal fun parse(xmlFile: File): ParseResult {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        // Disable external DTD loading to support offline/CI builds
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      }
    val doc = factory.newDocumentBuilder().parse(xmlFile)
    val root = doc.documentElement

    return ParseResult(
      moduleCounters = parseModuleCounters(root),
      files = parseFiles(root),
      classes = parseClasses(root),
    )
  }

  private fun parseModuleCounters(root: Element): Map<CoverageMetric, Counter> = parseDirectCounters(root)

  /**
   * Reads only the `<counter>` elements that are direct children of [element], ignoring counters nested inside
   * descendant elements (e.g. a `<class>` element's own counters versus its child `<method>` counters).
   */
  private fun parseDirectCounters(element: Element): Map<CoverageMetric, Counter> {
    val counters = mutableMapOf<CoverageMetric, Counter>()
    val children = element.childNodes

    for (i in 0 until children.length) {
      val node = children.item(i)
      if (node is Element && node.tagName == "counter") {
        val metric = CoverageMetric.entries.firstOrNull { it.name == node.getAttribute("type") } ?: continue

        counters[metric] =
          Counter(missed = node.getAttribute("missed").toInt(), covered = node.getAttribute("covered").toInt())
      }
    }

    return counters
  }

  private fun parseClasses(root: Element): List<ClassCoverage> {
    val classes = mutableListOf<ClassCoverage>()
    val packages = root.getElementsByTagName("package")

    for (i in 0 until packages.length) {
      val pkg = packages.item(i) as Element
      val packageName = pkg.getAttribute("name")
      val classNodes = pkg.childNodes

      for (j in 0 until classNodes.length) {
        val node = classNodes.item(j)
        if (node is Element && node.tagName == "class") {
          classes += parseClass(node, packageName)
        }
      }
    }

    return classes
  }

  private fun parseClass(cls: Element, packageName: String): ClassCoverage {
    val slashName = cls.getAttribute("name")
    val fqcn = slashName.replace('/', '.')

    return ClassCoverage(
      fqcn = fqcn,
      sourcefilename = cls.getAttribute("sourcefilename"),
      packageName = packageName,
      counters = parseDirectCounters(cls),
    )
  }

  private fun parseFiles(root: Element): List<FileCoverage> {
    val files = mutableListOf<FileCoverage>()
    val packages = root.getElementsByTagName("package")

    for (i in 0 until packages.length) {
      val pkg = packages.item(i) as Element
      val packageName = pkg.getAttribute("name")
      val sourceFiles = pkg.getElementsByTagName("sourcefile")

      for (j in 0 until sourceFiles.length) {
        files += parseSourceFile(sourceFiles.item(j) as Element, packageName)
      }
    }

    return files
  }

  private fun parseSourceFile(sf: Element, packageName: String): FileCoverage {
    val uncoveredLines = mutableListOf<Int>()
    val partiallyCoveredLines = mutableListOf<Int>()
    val branchLines = mutableListOf<BranchLine>()
    val lines = sf.getElementsByTagName("line")
    var missedInstructionsCold = 0
    var missedInstructionsResidual = 0

    for (i in 0 until lines.length) {
      val line = lines.item(i) as Element
      val nr = line.getAttribute("nr").toInt()
      val mi = line.getAttribute("mi").toIntOrNull() ?: 0
      val ci = line.getAttribute("ci").toIntOrNull() ?: 0
      val missed = line.getAttribute("mb").toIntOrNull() ?: 0
      val covered = line.getAttribute("cb").toIntOrNull() ?: 0

      when {
        mi > 0 && ci == 0 -> {
          uncoveredLines += nr
          missedInstructionsCold += mi
        }
        mi > 0 -> {
          partiallyCoveredLines += nr
          missedInstructionsResidual += mi
        }
      }

      if (missed > 0) branchLines += BranchLine(nr, missed, missed + covered, fullyInstructionCovered = mi == 0)
    }

    val (missedBranches, totalBranches) = parseBranchCounter(sf)

    return FileCoverage(
      name = sf.getAttribute("name"),
      packageName = packageName,
      uncoveredLines = uncoveredLines.sorted(),
      branchLines = branchLines.sortedBy { it.line },
      missedBranches = missedBranches,
      totalBranches = totalBranches,
      partiallyCoveredLines = partiallyCoveredLines.sorted(),
      missedInstructionsCold = missedInstructionsCold,
      missedInstructionsResidual = missedInstructionsResidual,
    )
  }

  private fun parseBranchCounter(sf: Element): Pair<Int, Int> {
    val counters = sf.getElementsByTagName("counter")

    for (i in 0 until counters.length) {
      val c = counters.item(i) as Element

      if (c.getAttribute("type") == "BRANCH") {
        val missed = c.getAttribute("missed").toIntOrNull() ?: 0
        val covered = c.getAttribute("covered").toIntOrNull() ?: 0

        return Pair(missed, missed + covered)
      }
    }

    return Pair(0, 0)
  }
}
