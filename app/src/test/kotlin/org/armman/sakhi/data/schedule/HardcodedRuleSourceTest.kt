package org.armman.sakhi.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * CR-022a cases RS-1 … RS-6, plus the SRS formula boundaries the constants encode.
 *
 * These tests double as the executable record of what each SRS rule means. Where the SRS is
 * ambiguous the test states the reading we implemented and names the open question, so a later
 * ARMMAN answer shows up as a failing test rather than a silent behaviour change.
 */
class HardcodedRuleSourceTest {

  private lateinit var rules: HardcodedRuleSource

  @Before
  fun setUp() {
    rules = HardcodedRuleSource()
  }

  /**
   * RS-5. The literal is spelled out rather than compared to the constant, so that changing the
   * constant cannot silently pass: this value has to match what the backend actually seeded
   * (their PR #102), or `POST /visit-schedules/bulk` rejects every upload with
   * `UNKNOWN_RULE_VERSION`.
   */
  @Test
  fun `rule version matches the uuid the backend seeded`() {
    assertEquals("22222222-2222-4222-8222-222222222222", rules.ruleVersion(VisitCodeType.ANC))
    assertEquals(HardcodedRuleSource.SEEDED_RULE_VERSION_ID, rules.ruleVersion(VisitCodeType.ANC))
  }

  /**
   * M2 has one Kotlin file behind every family, so the version stamp does not yet vary by
   * [VisitCodeType] — unlike `GoRulesRuleSource` (CR-032), which returns a different version per
   * independently-published rule set. Pinned here so a future change that starts differentiating
   * by family is a deliberate decision, not an accidental drift.
   */
  @Test
  fun `rule version is the same constant for every visit type in M2`() {
    VisitCodeType.entries.forEach { visitType ->
      assertEquals(HardcodedRuleSource.SEEDED_RULE_VERSION_ID, rules.ruleVersion(visitType))
    }
  }

  // ---- ANC count, FR-S-3.1: ((EDD − registration) / 30) + 1, uncapped -------------------------

  @Test
  fun `ANC-1 registration on the LMP date yields ten visits`() {
    assertEquals(10, ancCount(registration = "2026-01-01", edd = "2026-10-08"))
  }

  @Test
  fun `ANC-2 registration thirty days before EDD yields two visits`() {
    assertEquals(2, ancCount(registration = "2026-09-08", edd = "2026-10-08"))
  }

  @Test
  fun `ANC-3 registration twenty-nine days before EDD yields one visit`() {
    // Integer division floors — 29/30 = 0.
    assertEquals(1, ancCount(registration = "2026-09-09", edd = "2026-10-08"))
  }

  @Test
  fun `ANC-4 registration on the EDD yields one visit`() {
    assertEquals(1, ancCount(registration = "2026-10-08", edd = "2026-10-08"))
  }

  @Test
  fun `ANC-5 registration after the EDD still yields one visit and never a negative count`() {
    assertEquals(1, ancCount(registration = "2026-10-20", edd = "2026-10-08"))
  }

  @Test
  fun `ANC-6 mid-pregnancy registration`() {
    // 2026-05-01 → 2026-10-08 is 160 days; 160/30 = 5, +1 = 6.
    assertEquals(6, ancCount(registration = "2026-05-01", edd = "2026-10-08"))
  }

  // ---- ANC windows, FR-S-3.2 / FR-S-3.3 --------------------------------------------------------

  @Test
  fun `ANC-8 ANC1 window is one-sided Day 0 to Day plus 5`() {
    val scheduled = LocalDate.of(2026, 8, 4)
    val window = rules.window(VisitCodeType.ANC, sequenceNo = 1, scheduledDate = scheduled)

    assertEquals(scheduled, window.start)
    assertEquals(scheduled.plusDays(5), window.end)
    // Regression guard — the ±5 default must not leak into ANC1.
    assertEquals(6, window.lengthInDays)
  }

  @Test
  fun `ANC-10 ANC2 onwards use a symmetric five-day window`() {
    val scheduled = LocalDate.of(2026, 9, 3)
    val window = rules.window(VisitCodeType.ANC, sequenceNo = 2, scheduledDate = scheduled)

    assertEquals(scheduled.minusDays(5), window.start)
    assertEquals(scheduled.plusDays(5), window.end)
    assertEquals(11, window.lengthInDays)
  }

  @Test
  fun `PE-4 post-EDD window is one-sided five days forward`() {
    val scheduled = LocalDate.of(2026, 10, 16)
    val window = rules.window(VisitCodeType.ANC_POST_EDD, sequenceNo = 1, scheduledDate = scheduled)

    assertEquals(scheduled, window.start)
    assertEquals(scheduled.plusDays(5), window.end)
  }

  @Test
  fun `post-EDD trigger grace is seven days and the visit lands on EDD plus eight`() {
    assertEquals(7, rules.postEddGraceDays())
    assertEquals(8, rules.postEddOffsetDays())
  }

  // ---- PP, pending ARMMAN Q1 -------------------------------------------------------------------

  @Test
  fun `PP-2 scheduled offsets are 0 15 58 88 118 pending ARMMAN confirmation`() {
    // Reconciled reading: the SRS "Anchor" column is the scheduled date. PP5's stated 105
    // contradicts the SRS's own formula (PP4 88 + 30 = 118); 118 is implemented.
    assertEquals(
      listOf(0, 15, 58, 88, 118),
      (1..5).map { rules.scheduledOffsetDays(VisitCodeType.PP, it) },
    )
  }

  @Test
  fun `PP-3 PP1 window is a fixed range Day 0 to Day 14`() {
    val delivery = LocalDate.of(2026, 6, 1)
    val window = rules.fixedRangeWindow(VisitCodeType.PP, 1, delivery)!!

    assertEquals(delivery, window.start)
    assertEquals(delivery.plusDays(14), window.end)
  }

  @Test
  fun `PP-4 PP2 window is a fixed range Day 15 to Day 28`() {
    val delivery = LocalDate.of(2026, 6, 1)
    val window = rules.fixedRangeWindow(VisitCodeType.PP, 2, delivery)!!

    assertEquals(delivery.plusDays(15), window.start)
    assertEquals(delivery.plusDays(28), window.end)
  }

  @Test
  fun `only PP1 and PP2 are fixed-range, PP3 onwards are symmetric`() {
    assertTrue(rules.usesFixedRangeWindow(VisitCodeType.PP, 1))
    assertTrue(rules.usesFixedRangeWindow(VisitCodeType.PP, 2))
    assertFalse(rules.usesFixedRangeWindow(VisitCodeType.PP, 3))
    assertTrue(rules.usesFixedRangeWindow(VisitCodeType.NN, 1))
    assertFalse(rules.usesFixedRangeWindow(VisitCodeType.ANC, 1))
  }

  @Test
  fun `PP-5 PP3 to PP5 windows are symmetric and line up with the SRS close dates`() {
    val delivery = LocalDate.of(2026, 6, 1)
    // PP3 scheduled at +58 with ±5 gives 53→63, exactly the SRS's window-close column.
    val pp3 = rules.window(
      VisitCodeType.PP,
      sequenceNo = 3,
      scheduledDate = delivery.plusDays(58),
    )
    assertEquals(delivery.plusDays(53), pp3.start)
    assertEquals(delivery.plusDays(63), pp3.end)
  }

  @Test
  fun `PP1 and PP2 reject the symmetric window path`() {
    // They are fixed ranges from the delivery date; calling the generic path is a programming error.
    listOf(1, 2).forEach { seq ->
      runCatching { rules.window(VisitCodeType.PP, seq, LocalDate.of(2026, 6, 1)) }
        .onSuccess { error("PP$seq should not resolve through the symmetric window path") }
    }
  }

  // ---- NN, FR-S-2.2A scenarios -----------------------------------------------------------------

  @Test
  fun `NN-1 scenario A form filled on day zero generates both NN visits`() {
    assertEquals(2, nnCount(delivery = "2026-06-01", filled = "2026-06-01"))
  }

  @Test
  fun `NN-2 scenario A boundary day fourteen still generates both`() {
    assertEquals(2, nnCount(delivery = "2026-06-01", filled = "2026-06-15"))
  }

  @Test
  fun `NN-4 scenario B form filled day fifteen skips NN1 entirely`() {
    // Skipped means not generated AND not marked missed — hence a count of 1, not 2.
    assertEquals(1, nnCount(delivery = "2026-06-01", filled = "2026-06-16"))
  }

  @Test
  fun `NN-7 scenario B boundary day twenty-seven`() {
    assertEquals(1, nnCount(delivery = "2026-06-01", filled = "2026-06-28"))
  }

  @Test
  fun `NN-8 scenario C form filled day twenty-eight generates NN2 only`() {
    assertEquals(1, nnCount(delivery = "2026-06-01", filled = "2026-06-29"))
  }

  @Test
  fun `NN-9 form filled after day twenty-eight generates nothing`() {
    // Not covered by the SRS — flagged to ARMMAN. Failing safe with zero rows beats inventing one.
    assertEquals(0, nnCount(delivery = "2026-06-01", filled = "2026-06-30"))
  }

  @Test
  fun `NN-6 scenario B window opens on the fill date and still closes at day twenty-eight`() {
    val delivery = LocalDate.of(2026, 6, 1)
    val window = rules.fixedRangeWindow(
      VisitCodeType.NN,
      sequenceNo = 2,
      anchorDate = delivery,
      notBefore = delivery.plusDays(19),
    )!!

    assertEquals(delivery.plusDays(19), window.start)
    assertEquals(delivery.plusDays(28), window.end)
  }

  @Test
  fun `NN-10 NN windows are fixed ranges not plus-or-minus N`() {
    val delivery = LocalDate.of(2026, 6, 1)
    val nn1 = rules.fixedRangeWindow(VisitCodeType.NN, 1, delivery, notBefore = delivery)!!

    assertEquals(delivery, nn1.start)
    assertEquals(delivery.plusDays(14), nn1.end)
  }

  /**
   * NN-4 at window level. Scenario B asks for NN1 after its range has closed; the SRS says the
   * visit is skipped, so this must be an ordinary null rather than a crash — the old
   * implementation threw from [VisitWindow]'s `require`.
   */
  @Test
  fun `NN-4 a closed fixed-range window returns null instead of throwing`() {
    val delivery = LocalDate.of(2026, 6, 1)

    assertNull(
      rules.fixedRangeWindow(VisitCodeType.NN, 1, delivery, notBefore = delivery.plusDays(20)),
    )
    assertNull(
      rules.fixedRangeWindow(VisitCodeType.NN, 2, delivery, notBefore = delivery.plusDays(29)),
    )
  }

  // ---- INC, two-formula approach ---------------------------------------------------------------

  @Test
  fun `INC-1 early registration yields ten additional visits`() {
    // Round((365 − 58) / 30) = Round(10.23) = 10, so INC1…INC11.
    assertEquals(10, incCount(dob = "2026-06-01", registration = "2026-06-10"))
  }

  @Test
  fun `INC-4 boundary day fifty-eight is early registration`() {
    assertTrue(
      rules.isEarlyIncRegistration(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 29)),
    )
    assertEquals(10, incCount(dob = "2026-06-01", registration = "2026-07-29"))
  }

  @Test
  fun `INC-5 boundary day fifty-nine is late registration`() {
    assertFalse(
      rules.isEarlyIncRegistration(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 30)),
    )
  }

  /**
   * A registration date before the DOB is bad data, not an early registration. Treating a negative
   * age as "early" would silently produce a full 11-visit INC schedule from a typo.
   */
  @Test
  fun `a registration date before the DOB is not an early registration`() {
    assertFalse(
      rules.isEarlyIncRegistration(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 5, 20)),
    )
  }

  @Test
  fun `INC-7 late registration count formula`() {
    // Day 92: Round((365 − 92) / 30) = Round(9.1) = 9 additional, so 10 visits with INC1.
    assertEquals(9, incCount(dob = "2026-06-01", registration = "2026-09-01"))
  }

  @Test
  fun `INC-12 registration close to twelve months yields no additional visits`() {
    // Day 360: Round(5/30) = 0. INC1 alone, never a negative count.
    assertEquals(0, incCount(dob = "2026-06-01", registration = "2027-05-27"))
  }

  @Test
  fun `INC-9 cutoff is DOB plus 370`() {
    assertEquals(370, rules.cutoffDays(VisitCodeType.INC))
  }

  @Test
  fun `only INC has a cutoff`() {
    listOf(VisitCodeType.ANC, VisitCodeType.PP, VisitCodeType.NN, VisitCodeType.CCV)
      .forEach { assertNull("$it should be uncapped", rules.cutoffDays(it)) }
  }

  @Test
  fun `INC first visit anchors to DOB plus 58 for an early registration`() {
    assertEquals(58, rules.incFirstVisitOffsetDays())
  }

  // ---- HR, FR-S-3.4, pending ARMMAN Q2 ---------------------------------------------------------

  /** SRS CCV risk-state table: "HR visit in 30 days", where ANC/INC use 15 (FR-S-3.4). */
  @Test
  fun `CCV high-risk cadence is thirty days, not the fifteen used by ANC and INC`() {
    assertEquals(30, rules.hrOffsetDays(VisitCodeType.CCV))
    assertEquals(30, rules.hrOffsetDays(VisitCodeType.CCV_HR))
    assertEquals(15, rules.hrOffsetDays(VisitCodeType.ANC))
  }

  @Test
  fun `HR-2 HR offset is fifteen days with a two-day window`() {
    assertEquals(15, rules.hrOffsetDays(VisitCodeType.ANC))
    assertEquals(15, rules.hrOffsetDays(VisitCodeType.INC))

    val scheduled = LocalDate.of(2026, 3, 21)
    val window = rules.window(VisitCodeType.ANC_HR, sequenceNo = 1, scheduledDate = scheduled)
    assertEquals(scheduled.minusDays(2), window.start)
    assertEquals(scheduled.plusDays(2), window.end)
    assertEquals(5, window.lengthInDays)
  }

  @Test
  fun `HR-4 HR visits are generated per detection pending ARMMAN confirmation`() {
    assertTrue(rules.hrPerDetection())
  }

  // SR-NN-01 — the neonatal phase never generates HR visits.
  @Test
  fun `HR-6 NN does not support HR visits`() {
    assertFalse(rules.supportsHrVisits(VisitCodeType.NN))
    assertTrue(rules.supportsHrVisits(VisitCodeType.ANC))
    assertTrue(rules.supportsHrVisits(VisitCodeType.INC))
    assertTrue(rules.supportsHrVisits(VisitCodeType.CCV))
  }

  // ---- Escalation, FR-S-3.5 / FR-S-3.6 ---------------------------------------------------------

  /**
   * The SRS names only ANC and INC in FR-S-3.5. CCV's policy is an assumption — pinned here so an
   * ARMMAN answer (open question Q4) surfaces as a failing test rather than a silent change.
   */
  @Test
  fun `CCV escalation is assumed to follow ANC and INC pending ARMMAN confirmation`() {
    assertEquals(EscalationPolicy.AFTER_TWO_CONSECUTIVE, rules.escalationPolicy(VisitCodeType.CCV))
  }

  @Test
  fun `two consecutive misses escalate for ANC and INC, one miss escalates for the rest`() {
    assertEquals(EscalationPolicy.AFTER_TWO_CONSECUTIVE, rules.escalationPolicy(VisitCodeType.ANC))
    assertEquals(EscalationPolicy.AFTER_TWO_CONSECUTIVE, rules.escalationPolicy(VisitCodeType.INC))

    listOf(
      VisitCodeType.ANC_HR,
      VisitCodeType.INC_HR,
      VisitCodeType.NN,
      VisitCodeType.PP,
      VisitCodeType.ANC_POST_EDD,
    ).forEach {
      assertEquals("$it should escalate immediately", EscalationPolicy.IMMEDIATE, rules.escalationPolicy(it))
    }
  }

  // ---- Intervals -------------------------------------------------------------------------------

  @Test
  fun `ANC and INC repeat every thirty days, CCV every sixty`() {
    assertEquals(30, rules.intervalDays(VisitCodeType.ANC))
    assertEquals(30, rules.intervalDays(VisitCodeType.INC))
    assertEquals(60, rules.intervalDays(VisitCodeType.CCV))
  }

  @Test
  fun `PP and NN have no interval because they use explicit offset tables`() {
    listOf(VisitCodeType.PP, VisitCodeType.NN).forEach { type ->
      runCatching { rules.intervalDays(type) }
        .onSuccess { error("$type should not expose a fixed interval") }
    }
  }

  /** DELIVERY is an event the Sakhi records, not a visit the engine schedules a window for. */
  @Test
  fun `DELIVERY has no window rather than borrowing another family's constant`() {
    runCatching { rules.window(VisitCodeType.DELIVERY, 1, LocalDate.of(2026, 6, 1)) }
      .onSuccess { error("DELIVERY should not resolve to a window") }
  }

  @Test
  fun `an out-of-range PP sequence fails with a clear message`() {
    val result = runCatching { rules.scheduledOffsetDays(VisitCodeType.PP, 6) }
    assertTrue("PP6 should be rejected", result.isFailure)
    assertTrue(
      "Message should name the schedule size, got: ${result.exceptionOrNull()?.message}",
      result.exceptionOrNull()?.message?.contains("PP schedule") == true,
    )
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  private fun ancCount(registration: String, edd: String) = rules.visitCount(
    VisitCodeType.ANC,
    ScheduleContext(
      localBeneficiaryId = "ben-1",
      registrationDate = LocalDate.parse(registration),
      edd = LocalDate.parse(edd),
    ),
  )

  private fun nnCount(delivery: String, filled: String) = rules.visitCount(
    VisitCodeType.NN,
    ScheduleContext(
      localBeneficiaryId = "ben-1",
      registrationDate = LocalDate.parse(delivery),
      deliveryDate = LocalDate.parse(delivery),
      deliveryFormFilledOn = LocalDate.parse(filled),
    ),
  )

  private fun incCount(dob: String, registration: String) = rules.visitCount(
    VisitCodeType.INC,
    ScheduleContext(
      localBeneficiaryId = "ben-1",
      registrationDate = LocalDate.parse(registration),
      dob = LocalDate.parse(dob),
    ),
  )
}
