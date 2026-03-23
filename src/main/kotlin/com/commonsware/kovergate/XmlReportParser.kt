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

data class Counter(val missed: Int, val covered: Int)

data class FileCoverage(
  val name: String,
  val packageName: String,
  val uncoveredLines: List<Int>,
  val missedBranchLines: List<Int>,
  val missedBranches: Int = 0,
  val totalBranches: Int = 0,
)

data class ParseResult(val moduleCounters: Map<CoverageMetric, Counter>, val files: List<FileCoverage>)

object XmlReportParser {
  fun parse(xmlFile: File): ParseResult {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        // Disable external DTD loading to support offline/CI builds
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      }
    val doc = factory.newDocumentBuilder().parse(xmlFile)
    val root = doc.documentElement

    return ParseResult(moduleCounters = parseModuleCounters(root), files = parseFiles(root))
  }

  private fun parseModuleCounters(root: Element): Map<CoverageMetric, Counter> {
    val counters = mutableMapOf<CoverageMetric, Counter>()
    val children = root.childNodes

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
    val missedBranchLines = mutableListOf<Int>()
    val lines = sf.getElementsByTagName("line")

    for (i in 0 until lines.length) {
      val line = lines.item(i) as Element
      val nr = line.getAttribute("nr").toInt()
      if ((line.getAttribute("mi").toIntOrNull() ?: 0) > 0) uncoveredLines += nr
      if ((line.getAttribute("mb").toIntOrNull() ?: 0) > 0) missedBranchLines += nr
    }

    // Read file-level BRANCH counter for the "(N of M)" display in console
    // output
    var missedBranches = 0
    var totalBranches = 0
    val counters = sf.getElementsByTagName("counter")

    for (i in 0 until counters.length) {
      val c = counters.item(i) as Element

      if (c.getAttribute("type") == "BRANCH") {
        val missed = c.getAttribute("missed").toIntOrNull() ?: 0
        val covered = c.getAttribute("covered").toIntOrNull() ?: 0
        missedBranches = missed
        totalBranches = missed + covered
        break
      }
    }

    return FileCoverage(
      name = sf.getAttribute("name"),
      packageName = packageName,
      uncoveredLines = uncoveredLines.sorted(),
      missedBranchLines = missedBranchLines.sorted(),
      missedBranches = missedBranches,
      totalBranches = totalBranches,
    )
  }
}
