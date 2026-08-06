package org.armman.sakhi.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Guards the CR-032 swap point.
 *
 * The M3 estimate for moving scheduling rules into GoRules assumes it is a one-line Hilt binding
 * change. That only holds while every rule value flows through [ScheduleRuleSource] and no
 * scheduling constant is inlined anywhere else. **If these tests are weakened, the M3 estimate
 * silently becomes wrong** — so treat a failure here as a design regression, not a test problem.
 *
 * Cases RS-1 … RS-4 (generator driven by a fake rule source) arrive with the generator in CR-022b;
 * this file covers what exists at CR-022a — substitutability and the no-inline-constants rule.
 */
class ScheduleRuleSourceSeamTest {

  // RS-4 — whatever version the source reports is what gets stamped on generated rows.
  @Test
  fun `rule version comes from the rule source, not from a constant in the caller`() {
    val source: ScheduleRuleSource = FakeRuleSource(ruleVersion = "test-v9")

    assertEquals("test-v9", source.ruleVersion)
    assertNotEquals(
      "A fake must be able to differ from the production source, or the seam is decorative",
      HardcodedRuleSource().ruleVersion,
      source.ruleVersion,
    )
  }

  // RS-1 — a fake can impose a cadence the SRS never mentions, proving values are read not known.
  @Test
  fun `a fake rule source can impose a ten-day cadence`() {
    val source: ScheduleRuleSource = FakeRuleSource(interval = 10)

    assertEquals(10, source.intervalDays(VisitCodeType.ANC))
    assertNotEquals(
      HardcodedRuleSource().intervalDays(VisitCodeType.ANC),
      source.intervalDays(VisitCodeType.ANC),
    )
  }

  // RS-2
  @Test
  fun `a fake rule source can impose a two-day window`() {
    val source: ScheduleRuleSource = FakeRuleSource(windowDays = 2)
    val scheduled = LocalDate.of(2026, 8, 4)

    val window = source.window(VisitCodeType.ANC, sequenceNo = 2, scheduledDate = scheduled)

    assertEquals(scheduled.minusDays(2), window.start)
    assertEquals(scheduled.plusDays(2), window.end)
    assertEquals(5, window.lengthInDays)
  }

  // RS-3
  @Test
  fun `a fake rule source can impose a fixed visit count independent of the EDD`() {
    val source: ScheduleRuleSource = FakeRuleSource(count = 3)
    val context = ScheduleContext(
      localBeneficiaryId = "ben-1",
      registrationDate = LocalDate.of(2026, 1, 1),
      edd = LocalDate.of(2026, 10, 8),
    )

    // The real source returns 10 for this context; the fake overrides it entirely.
    assertEquals(3, source.visitCount(VisitCodeType.ANC, context))
    assertEquals(10, HardcodedRuleSource().visitCount(VisitCodeType.ANC, context))
  }

  /**
   * The fixed-range window path must be reachable through the interface. It was originally a
   * concrete-only method on [HardcodedRuleSource], which meant a generator wanting a PP1 or NN
   * window had to depend on the implementation — silently voiding the swap this file exists to
   * protect. Asserting it through the interface type is what keeps that from regressing.
   */
  @Test
  fun `fixed-range windows are reachable through the interface, not only the concrete class`() {
    val source: ScheduleRuleSource = FakeRuleSource(fixedRangeDays = 0 to 9)
    val anchor = LocalDate.of(2026, 6, 1)

    assertTrue(source.usesFixedRangeWindow(VisitCodeType.NN, sequenceNo = 1))

    val window = source.fixedRangeWindow(VisitCodeType.NN, 1, anchor)
    assertEquals(anchor, window!!.start)
    assertEquals(anchor.plusDays(9), window.end)
  }

  /** A closed window is an ordinary null, not an exception — NN scenario B depends on it. */
  @Test
  fun `a closed fixed-range window is null through the interface`() {
    val source: ScheduleRuleSource = FakeRuleSource(fixedRangeDays = 0 to 9)
    val anchor = LocalDate.of(2026, 6, 1)

    assertNull(source.fixedRangeWindow(VisitCodeType.NN, 1, anchor, notBefore = anchor.plusDays(10)))
  }

  @Test
  fun `the offset table and INC banding are reachable through the interface`() {
    val source: ScheduleRuleSource = FakeRuleSource(
      offsetTable = listOf(0, 20, 40),
      earlyIncMaxDay = 30L,
      incFirstVisitOffset = 20,
    )

    assertEquals(40, source.scheduledOffsetDays(VisitCodeType.PP, sequenceNo = 3))
    assertEquals(20, source.incFirstVisitOffsetDays())

    val dob = LocalDate.of(2026, 6, 1)
    assertTrue(source.isEarlyIncRegistration(dob, dob.plusDays(30)))
    assertFalse(source.isEarlyIncRegistration(dob, dob.plusDays(31)))
    assertFalse("a pre-DOB date is bad data, not early", source.isEarlyIncRegistration(dob, dob.minusDays(1)))
  }

  @Test
  fun `both implementations satisfy the same contract`() {
    val implementations: List<ScheduleRuleSource> = listOf(HardcodedRuleSource(), FakeRuleSource())

    implementations.forEach { source ->
      assertTrue("ruleVersion must not be blank", source.ruleVersion.isNotBlank())
      assertTrue("HR offset must be positive", source.hrOffsetDays(VisitCodeType.ANC) > 0)
      assertTrue("ANC interval must be positive", source.intervalDays(VisitCodeType.ANC) > 0)
      assertTrue("ANC1 is never fixed-range", !source.usesFixedRangeWindow(VisitCodeType.ANC, 1))
      assertTrue("INC1 offset must be positive", source.incFirstVisitOffsetDays() > 0)
    }
  }

  /**
   * RS-6 — no scheduling constant may live outside [HardcodedRuleSource].
   *
   * Scans the production sources for bare integer literals. Day counts, intervals, window widths
   * and visit counts are what matter; 0/1/2 and ordinary index arithmetic are allowed because
   * forbidding them produces noise without catching real leaks.
   *
   * HTTP status codes are exempt by name ([EXEMPT_CONSTANT_PREFIXES]). They live in the sync
   * executor, they are transport concerns rather than clinical rules, and GoRules will never own
   * them. The exemption is by prefix rather than by file so that a scheduling constant smuggled
   * into the sync layer is still caught.
   *
   * Skipped rather than failed when the source tree cannot be located (some CI layouts run tests
   * from a different working directory). Code review is the real backstop; this catches the easy
   * regressions.
   */
  @Test
  fun `no scheduling constant is inlined outside HardcodedRuleSource`() {
    val scheduleDir = locateScheduleSourceDir()
    assumeTrue("Source tree not locatable from ${File("").absolutePath}", scheduleDir != null)

    val offenders = scheduleDir!!.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .filterNot { it.name == "HardcodedRuleSource.kt" }
      .flatMap { file ->
        file.readLines().asSequence()
          .mapIndexed { index, line -> (index + 1) to line }
          .filterNot { (_, line) -> line.isComment() || line.isExemptConstant() }
          .filter { (_, line) -> SUSPICIOUS_LITERAL.containsMatchIn(line) }
          .map { (lineNo, line) -> "${file.name}:$lineNo  ${line.trim()}" }
      }
      .toList()

    assertTrue(
      "Scheduling constants must live only in HardcodedRuleSource so the CR-032 GoRules swap " +
        "stays a binding change. Move these:\n" + offenders.joinToString("\n"),
      offenders.isEmpty(),
    )
  }

  private fun String.isExemptConstant(): Boolean {
    val declaration = CONSTANT_DECLARATION.find(this) ?: return false
    val name = declaration.groupValues[1]
    return EXEMPT_CONSTANT_PREFIXES.any { name.startsWith(it) }
  }

  private fun locateScheduleSourceDir(): File? = CANDIDATE_ROOTS
    .map { File(it, SCHEDULE_SOURCE_PATH) }
    .firstOrNull { it.isDirectory }

  private fun String.isComment(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
  }

  private companion object {
    const val SCHEDULE_SOURCE_PATH = "src/main/kotlin/org/armman/sakhi/data/schedule"

    /** Run from the module dir under Gradle, from the repo root in some IDE configurations. */
    val CANDIDATE_ROOTS = listOf(".", "app", "../app", "sakhi-mobile-app/app")

    /**
     * Integers of 3 or more, or 2-digit numbers that look like day counts. Deliberately excludes
     * 0/1/2 (indices, sequence starts) and anything inside a string literal or an annotation.
     */
    val SUSPICIOUS_LITERAL = Regex("""(?<![\w."'])\d{2,}(?![\w."'])""")

    val CONSTANT_DECLARATION = Regex("""\bconst\s+val\s+([A-Z][A-Z0-9_]*)\s*[:=]""")

    /**
     * Named constants exempt from the scan. `HTTP_` covers status codes in the sync executor —
     * transport, not clinical rules. Keep this list short: every entry is a hole in the guard.
     */
    val EXEMPT_CONSTANT_PREFIXES = listOf("HTTP_")
  }
}
