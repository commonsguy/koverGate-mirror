package com.commonsware.kovergate

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class XmlReportParserTest {

  @TempDir lateinit var tempDir: File

  private fun parseXml(xml: String): ParseResult {
    val file = File(tempDir, "report.xml")
    file.writeText(xml)
    return XmlReportParser.parse(file)
  }

  @Test
  fun `parses module-level LINE and BRANCH counters`() {
    val result =
      parseXml(
        """
        <report name="test">
            <package name="com/example">
                <sourcefile name="Foo.kt">
                    <counter type="LINE" missed="1" covered="9"/>
                </sourcefile>
                <counter type="LINE" missed="1" covered="9"/>
            </package>
            <counter type="LINE" missed="2" covered="18"/>
            <counter type="BRANCH" missed="0" covered="4"/>
        </report>
        """
          .trimIndent()
      )

    val line = result.moduleCounters[CoverageMetric.LINE]!!
    assertEquals(2, line.missed)
    assertEquals(18, line.covered)

    val branch = result.moduleCounters[CoverageMetric.BRANCH]!!
    assertEquals(0, branch.missed)
    assertEquals(4, branch.covered)
  }

  @Test
  fun `parses all five module-level metrics`() {
    val result =
      parseXml(
        """
        <report name="test">
            <counter type="LINE" missed="1" covered="99"/>
            <counter type="BRANCH" missed="2" covered="98"/>
            <counter type="INSTRUCTION" missed="3" covered="97"/>
            <counter type="METHOD" missed="4" covered="96"/>
            <counter type="CLASS" missed="5" covered="95"/>
        </report>
        """
          .trimIndent()
      )

    assertEquals(5, result.moduleCounters.size)
    assertEquals(Counter(missed = 1, covered = 99), result.moduleCounters[CoverageMetric.LINE])
    assertEquals(Counter(missed = 2, covered = 98), result.moduleCounters[CoverageMetric.BRANCH])
    assertEquals(Counter(missed = 3, covered = 97), result.moduleCounters[CoverageMetric.INSTRUCTION])
    assertEquals(Counter(missed = 4, covered = 96), result.moduleCounters[CoverageMetric.METHOD])
    assertEquals(Counter(missed = 5, covered = 95), result.moduleCounters[CoverageMetric.CLASS])
  }

  @Test
  fun `does not include package-level counters in module totals`() {
    val result =
      parseXml(
        """
        <report name="test">
            <package name="com/example">
                <counter type="LINE" missed="999" covered="999"/>
            </package>
            <counter type="LINE" missed="1" covered="9"/>
        </report>
        """
          .trimIndent()
      )

    val line = result.moduleCounters[CoverageMetric.LINE]!!
    assertEquals(1, line.missed)
    assertEquals(9, line.covered)
  }

  @Test
  fun `identifies uncovered lines where mi gt 0`() {
    val result =
      parseXml(
        """
        <report name="test">
            <package name="com/example">
                <sourcefile name="Bar.kt">
                    <line nr="10" mi="0" ci="3" mb="0" cb="0"/>
                    <line nr="15" mi="2" ci="0" mb="0" cb="0"/>
                    <line nr="20" mi="1" ci="0" mb="0" cb="0"/>
                </sourcefile>
            </package>
        </report>
        """
          .trimIndent()
      )

    val file = result.files.single()
    assertEquals("Bar.kt", file.name)
    assertEquals(listOf(15, 20), file.uncoveredLines)
    assertTrue(file.missedBranchLines.isEmpty())
  }

  @Test
  fun `identifies missed branch lines where mb gt 0`() {
    val result =
      parseXml(
        """
        <report name="test">
            <package name="com/example">
                <sourcefile name="Bar.kt">
                    <line nr="5" mi="0" ci="1" mb="2" cb="2"/>
                    <line nr="10" mi="0" ci="1" mb="0" cb="4"/>
                </sourcefile>
            </package>
        </report>
        """
          .trimIndent()
      )

    val file = result.files.single()
    assertEquals(listOf(5), file.missedBranchLines)
    assertTrue(file.uncoveredLines.isEmpty())
  }

  @Test
  fun `file with no missed lines has empty lists`() {
    val result =
      parseXml(
        """
        <report name="test">
            <package name="com/example">
                <sourcefile name="Clean.kt">
                    <line nr="1" mi="0" ci="1" mb="0" cb="0"/>
                    <line nr="2" mi="0" ci="1" mb="0" cb="2"/>
                </sourcefile>
            </package>
        </report>
        """
          .trimIndent()
      )

    val file = result.files.single()
    assertTrue(file.uncoveredLines.isEmpty())
    assertTrue(file.missedBranchLines.isEmpty())
  }

  @Test
  fun `includes package name on file coverage`() {
    val result =
      parseXml(
        """
        <report name="test">
            <package name="com/commonsware/json">
                <sourcefile name="Parser.kt">
                    <line nr="1" mi="1" ci="0" mb="0" cb="0"/>
                </sourcefile>
            </package>
        </report>
        """
          .trimIndent()
      )

    val file = result.files.single()
    assertEquals("com/commonsware/json", file.packageName)
  }

  @Test
  fun `empty report returns empty counters and files`() {
    val result = parseXml("<report name=\"empty\"></report>")

    assertTrue(result.moduleCounters.isEmpty())
    assertTrue(result.files.isEmpty())
  }

  @Test
  fun `unknown counter types are ignored`() {
    val result =
      parseXml(
        """
        <report name="test">
            <counter type="COMPLEXITY" missed="5" covered="10"/>
            <counter type="LINE" missed="1" covered="9"/>
        </report>
        """
          .trimIndent()
      )

    assertEquals(1, result.moduleCounters.size)
    assertNotNull(result.moduleCounters[CoverageMetric.LINE])
  }

  @Test
  fun `uncovered lines are returned in sorted order`() {
    val result =
      parseXml(
        """
        <report name="test">
            <package name="com/example">
                <sourcefile name="Foo.kt">
                    <line nr="30" mi="1" ci="0" mb="0" cb="0"/>
                    <line nr="10" mi="1" ci="0" mb="0" cb="0"/>
                    <line nr="20" mi="1" ci="0" mb="0" cb="0"/>
                </sourcefile>
            </package>
        </report>
        """
          .trimIndent()
      )

    assertEquals(listOf(10, 20, 30), result.files.single().uncoveredLines)
  }

  @Test
  fun `reads file-level branch counts for display`() {
    val result =
      parseXml(
        """
        <report name="test">
            <package name="com/example">
                <sourcefile name="Foo.kt">
                    <line nr="5" mi="0" ci="1" mb="2" cb="2"/>
                    <counter type="BRANCH" missed="2" covered="2"/>
                </sourcefile>
            </package>
        </report>
        """
          .trimIndent()
      )

    val file = result.files.single()
    assertEquals(2, file.missedBranches)
    assertEquals(4, file.totalBranches)
  }
}
