package org.armman.sakhi.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * CR-022b cases ANC-1 … ANC-13, PE-1 … PE-8, HR-1 … HR-6, plus the RS-1 … RS-4 seam proofs that
 * needed a generator to be written.
 *
 * Fixture: LMP 2026-01-01, EDD 2026-10-08 (LMP + 280).
 */
class AncScheduleGeneratorTest {

  private lateinit var generator: AncScheduleGenerator

  private val lmp = LocalDate.of(2026, 1, 1)
  private val edd = LocalDate.of(2026, 10, 8)
  private val createdAt = Instant.parse("2026-08-04T09:00:00Z")

  @Before
  fun setUp() {
    generator = AncScheduleGenerator(HardcodedRuleSource())
  }

  // ---- Count, FR-S-3.1 -------------------------------------------------------------------------

  @Test
  fun `ANC-1 registration on the LMP date produces ten visits`() {
    assertEquals(10, series(registration = lmp).size)
  }

  @Test
  fun `ANC-2 registration thirty days before EDD produces two visits`() {
    assertEquals(2, series(registration = LocalDate.of(2026, 9, 8)).size)
  }

  @Test
  fun `ANC-3 registration twenty-nine days before EDD produces one visit`() {
    assertEquals(1, series(registration = LocalDate.of(2026, 9, 9)).size)
  }

  @Test
  fun `ANC-4 registration on the EDD produces one visit`() {
    assertEquals(1, series(registration = edd).size)
  }

  @Test
  fun `ANC-5 registration after the EDD still produces ANC1 and never a negative count`() {
    val visits = series(registration = LocalDate.of(2026, 10, 20))

    assertEquals(1, visits.size)
    assertEquals("ANC1", visits.single().visitCode)
  }

  @Test
  fun `ANC-6 mid-pregnancy registration produces six visits`() {
    assertEquals(6, series(registration = LocalDate.of(2026, 5, 1)).size)
  }

  // ---- Dates and windows, FR-S-3.2 / FR-S-3.3 --------------------------------------------------

  @Test
  fun `ANC-7 ANC1 falls on the registration date itself`() {
    val registration = LocalDate.of(2026, 3, 15)
    assertEquals(registration, series(registration).first().scheduledDate)
  }

  /**
   * ANC-8. The regression this guards: applying the ±5 default to ANC1 would open its window five
   * days *before* the woman was registered.
   */
  @Test
  fun `ANC-8 ANC1 window is one-sided Day 0 to Day plus 5`() {
    val anc1 = series(lmp).first()

    assertEquals(lmp, anc1.windowStartDate)
    assertEquals(lmp.plusDays(5), anc1.windowEndDate)
    assertTrue("ANC1 must not open before registration", !anc1.windowStartDate.isBefore(lmp))
  }

  @Test
  fun `ANC-9 visits are thirty days apart with no cumulative drift`() {
    val visits = series(lmp)

    visits.zipWithNext { previous, next ->
      assertEquals(
        "Gap between ${previous.visitCode} and ${next.visitCode}",
        30L,
        ChronoUnit.DAYS.between(previous.scheduledDate, next.scheduledDate),
      )
    }
    // Absolute check too — ANC10 must land exactly on registration + 9 × 30, so a chaining bug
    // that drifts by a day per visit cannot hide behind the pairwise check above.
    assertEquals(lmp.plusDays(270), visits.last().scheduledDate)
  }

  @Test
  fun `ANC-10 ANC2 onwards use a symmetric five-day window`() {
    val anc2 = series(lmp)[1]

    assertEquals(anc2.scheduledDate.minusDays(5), anc2.windowStartDate)
    assertEquals(anc2.scheduledDate.plusDays(5), anc2.windowEndDate)
  }

  @Test
  fun `ANC-11 visit code and sequence number agree`() {
    series(lmp).forEachIndexed { index, visit ->
      assertEquals("ANC${index + 1}", visit.visitCode)
      assertEquals(index + 1, visit.sequenceNo)
      assertEquals(VisitCodeType.ANC, visit.visitType)
    }
  }

  @Test
  fun `ANC-12 every regular ANC row anchors to the registration date`() {
    series(lmp).forEach {
      assertEquals(AnchorType.REGISTRATION, it.anchorType)
      assertEquals(lmp, it.anchorDate)
    }
  }

  @Test
  fun `ANC-13 no regular ANC visit is scheduled beyond the EDD`() {
    listOf(lmp, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 9, 8)).forEach { registration ->
      series(registration).forEach {
        assertTrue(
          "${it.visitCode} on ${it.scheduledDate} is past the EDD $edd",
          !it.scheduledDate.isAfter(edd),
        )
      }
    }
  }

  @Test
  fun `every generated row carries the rule version and a unique id`() {
    val visits = series(lmp)

    assertTrue(visits.all { it.generatedByRuleVersion == HardcodedRuleSource().ruleVersion })
    assertEquals(visits.size, visits.map { it.localScheduleUuid }.distinct().size)
    assertTrue(visits.all { it.status == VisitScheduleStatus.GENERATED })
    assertTrue(visits.all { it.serverScheduleId == null })
  }

  @Test
  fun `generating without an EDD fails loudly rather than producing a wrong schedule`() {
    val result = runCatching {
      generator.generateSeries(
        ScheduleContext(localBeneficiaryId = "ben-1", registrationDate = lmp),
      )
    }
    assertTrue("An ANC schedule without an EDD is meaningless", result.isFailure)
  }

  // ---- Post-EDD, SR-ANC-01 ---------------------------------------------------------------------

  @Test
  fun `PE-1 no delivery form by EDD plus 7 generates one post-EDD visit on EDD plus 8`() {
    val visit = postEdd(regularCount = 8, filledOn = null, asOf = edd.plusDays(8))

    assertNotNull(visit)
    assertEquals(edd.plusDays(8), visit!!.scheduledDate)
    assertEquals(VisitCodeType.ANC_POST_EDD, visit.visitType)
  }

  @Test
  fun `PE-2 a delivery form filed in time generates nothing`() {
    assertNull(postEdd(regularCount = 8, filledOn = edd.plusDays(2), asOf = edd.plusDays(8)))
  }

  @Test
  fun `PE-3 a delivery form filed exactly on EDD plus 7 still counts as in time`() {
    assertNull(postEdd(regularCount = 8, filledOn = edd.plusDays(7), asOf = edd.plusDays(8)))
  }

  @Test
  fun `a delivery form filed on EDD plus 8 is late and does not suppress the visit`() {
    assertNotNull(postEdd(regularCount = 8, filledOn = edd.plusDays(8), asOf = edd.plusDays(8)))
  }

  @Test
  fun `PE-4 post-EDD window is one-sided EDD plus 8 to EDD plus 13`() {
    val visit = postEdd(regularCount = 8, filledOn = null, asOf = edd.plusDays(8))!!

    assertEquals(edd.plusDays(8), visit.windowStartDate)
    assertEquals(edd.plusDays(13), visit.windowEndDate)
  }

  @Test
  fun `PE-5 with eight regular ANC visits the post-EDD visit is named ANC9`() {
    assertEquals("ANC9", postEdd(regularCount = 8, filledOn = null, asOf = edd.plusDays(8))!!.visitCode)
  }

  @Test
  fun `PE-6 with ten regular ANC visits the post-EDD visit is named ANC11`() {
    assertEquals("ANC11", postEdd(regularCount = 10, filledOn = null, asOf = edd.plusDays(8))!!.visitCode)
  }

  /** PE-7 — HR rows must not advance the numbering; only regular ANC visits count. */
  @Test
  fun `PE-7 naming counts regular ANC visits only`() {
    val regularCount = series(lmp).count { it.visitType == VisitCodeType.ANC }
    assertEquals(10, regularCount)
    assertEquals("ANC11", postEdd(regularCount, filledOn = null, asOf = edd.plusDays(8))!!.visitCode)
  }

  @Test
  fun `the post-EDD visit is not generated before its trigger date`() {
    assertNull(postEdd(regularCount = 8, filledOn = null, asOf = edd.plusDays(7)))
  }

  @Test
  fun `the post-EDD visit anchors to the EDD`() {
    val visit = postEdd(regularCount = 8, filledOn = null, asOf = edd.plusDays(8))!!

    assertEquals(AnchorType.EDD, visit.anchorType)
    assertEquals(edd, visit.anchorDate)
  }

  @Test
  fun `a missed post-EDD visit escalates immediately`() {
    val visit = postEdd(regularCount = 8, filledOn = null, asOf = edd.plusDays(8))!!
    assertEquals(EscalationPolicy.IMMEDIATE, visit.escalationPolicy)
  }

  // ---- HR, FR-S-3.4 ----------------------------------------------------------------------------

  /**
   * HR-1 — the rule most easily misread in the SRS. ANC3 was due 2026-03-01 but the Sakhi reached
   * the woman on 2026-03-06; the HR follow-up is 15 days from **that**, not from the due date.
   */
  @Test
  fun `HR-1 the HR visit anchors to the actual completion date, not the scheduled date`() {
    val anc3 = schedule("anc3", visitCode = "ANC3", sequenceNo = 3, scheduledDate = LocalDate.of(2026, 3, 1))
    val completedOn = LocalDate.of(2026, 3, 6)

    val hr = generator.generateHrVisit(context(lmp), anc3, completedOn, newUuid = { "hr1" })!!

    assertEquals(LocalDate.of(2026, 3, 21), hr.scheduledDate)
    assertTrue(
      "Must not anchor to the scheduled date",
      hr.scheduledDate != anc3.scheduledDate.plusDays(15),
    )
  }

  @Test
  fun `HR-2 the HR window is plus or minus two days`() {
    val hr = hrFor(completedOn = LocalDate.of(2026, 3, 6))!!

    assertEquals(hr.scheduledDate.minusDays(2), hr.windowStartDate)
    assertEquals(hr.scheduledDate.plusDays(2), hr.windowEndDate)
  }

  @Test
  fun `HR-3 the HR row records its trigger explicitly`() {
    val anc3 = schedule("anc3", visitCode = "ANC3", sequenceNo = 3)
    val completedOn = LocalDate.of(2026, 3, 6)

    val hr = generator.generateHrVisit(context(lmp), anc3, completedOn, newUuid = { "hr1" })!!

    assertEquals(AnchorType.ACTUAL_VISIT, hr.anchorType)
    assertEquals(completedOn, hr.anchorDate)
    assertEquals("anc3", hr.anchorVisitLocalUuid)
  }

  @Test
  fun `HR-4 a second detection produces a second HR visit`() {
    val first = hrFor(completedOn = LocalDate.of(2026, 3, 6), existingHrCount = 0)!!
    val second = hrFor(completedOn = LocalDate.of(2026, 5, 2), existingHrCount = 1)!!

    assertEquals(1, first.sequenceNo)
    assertEquals(2, second.sequenceNo)
    assertEquals("ANC-HR1", first.visitCode)
    assertEquals("ANC-HR2", second.visitCode)
  }

  /** HR-5 — an inserted HR visit must not move the regular chain. */
  @Test
  fun `HR-5 generating an HR visit does not disturb the regular ANC dates`() {
    val before = series(lmp).map { it.scheduledDate }

    generator.generateHrVisit(context(lmp), schedule("anc3"), LocalDate.of(2026, 3, 6))

    assertEquals(before, series(lmp).map { it.scheduledDate })
  }

  /** HR-6 / SR-NN-01 — the neonatal phase routes to referral instead of generating an HR visit. */
  @Test
  fun `HR-6 no HR visit is generated for a neonatal trigger`() {
    val nn1 = schedule("nn1", visitCode = "NN1", visitType = VisitCodeType.NN)

    assertNull(generator.generateHrVisit(context(lmp), nn1, LocalDate.of(2026, 6, 10)))
  }

  @Test
  fun `a missed HR visit escalates immediately`() {
    assertEquals(EscalationPolicy.IMMEDIATE, hrFor(LocalDate.of(2026, 3, 6))!!.escalationPolicy)
  }

  // ---- Seam proofs, RS-1 … RS-4 ----------------------------------------------------------------

  /**
   * RS-1. The generator produces a ten-day cadence when told to — a cadence the SRS never mentions.
   * If this passes, the generator reads its rules rather than knowing them, and the CR-032 GoRules
   * swap is a binding change.
   */
  @Test
  fun `RS-1 the generator honours a cadence supplied by a fake rule source`() {
    val fake = AncScheduleGenerator(FakeRuleSource(interval = 10, count = 3))

    val visits = fake.generateSeries(context(lmp), newUuid = { "id" }, createdAt = createdAt)

    assertEquals(listOf(lmp, lmp.plusDays(10), lmp.plusDays(20)), visits.map { it.scheduledDate })
  }

  // RS-2
  @Test
  fun `RS-2 the generator honours a window width supplied by a fake rule source`() {
    val fake = AncScheduleGenerator(FakeRuleSource(windowDays = 2, count = 2))

    val anc2 = fake.generateSeries(context(lmp), newUuid = { "id" }, createdAt = createdAt)[1]

    assertEquals(anc2.scheduledDate.minusDays(2), anc2.windowStartDate)
    assertEquals(anc2.scheduledDate.plusDays(2), anc2.windowEndDate)
  }

  // RS-3
  @Test
  fun `RS-3 the generator honours a visit count supplied by a fake rule source`() {
    val fake = AncScheduleGenerator(FakeRuleSource(count = 3))

    // The real rules would produce 10 for this context.
    assertEquals(3, fake.generateSeries(context(lmp), newUuid = { "id" }).size)
    assertEquals(10, series(lmp).size)
  }

  // RS-4
  @Test
  fun `RS-4 the rule version stamped on every row comes from the rule source`() {
    val fake = AncScheduleGenerator(FakeRuleSource(ruleVersion = "test-v9"))

    val visits = fake.generateSeries(context(lmp), newUuid = { "id" })

    assertTrue(visits.isNotEmpty())
    assertTrue(visits.all { it.generatedByRuleVersion == "test-v9" })
  }

  @Test
  fun `RS-4 the HR offset also comes from the rule source`() {
    val fake = AncScheduleGenerator(FakeRuleSource(hrOffset = 7))
    val completedOn = LocalDate.of(2026, 3, 6)

    val hr = fake.generateHrVisit(context(lmp), schedule("anc3"), completedOn, newUuid = { "hr" })!!

    assertEquals(completedOn.plusDays(7), hr.scheduledDate)
  }

  // ---- Determinism -----------------------------------------------------------------------------

  @Test
  fun `generation is deterministic for the same inputs`() {
    var counter = 0
    val ids = { "id-${counter++}" }

    val first = generator.generateSeries(context(lmp), newUuid = ids, createdAt = createdAt)
    counter = 0
    val second = generator.generateSeries(context(lmp), newUuid = ids, createdAt = createdAt)

    assertEquals(first, second)
  }

  @Test
  fun `the created-at stamp comes from the caller, not the wall clock`() {
    val visits = generator.generateSeries(context(lmp), newUuid = { "id" }, createdAt = createdAt)

    assertTrue(visits.all { it.createdAtEpochMillis == createdAt.toEpochMilli() })
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  private fun context(registration: LocalDate) = ScheduleContext(
    localBeneficiaryId = "ben-1",
    registrationDate = registration,
    lmp = lmp,
    edd = edd,
  )

  private fun series(registration: LocalDate): List<VisitScheduleEntity> {
    var counter = 0
    return generator.generateSeries(
      context(registration),
      newUuid = { "id-${counter++}" },
      createdAt = createdAt,
    )
  }

  private fun postEdd(regularCount: Int, filledOn: LocalDate?, asOf: LocalDate) =
    generator.generatePostEddVisit(
      context(lmp),
      regularAncCount = regularCount,
      deliveryFormFilledOn = filledOn,
      asOf = asOf,
      newUuid = { "post-edd" },
      createdAt = createdAt,
    )

  private fun hrFor(completedOn: LocalDate, existingHrCount: Int = 0) = generator.generateHrVisit(
    context(lmp),
    schedule("anc3", visitCode = "ANC3", sequenceNo = 3),
    completedOn,
    existingHrCount = existingHrCount,
    newUuid = { "hr-$existingHrCount" },
    createdAt = createdAt,
  )
}
