package org.armman.sakhi.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * CR-022c cases NN-1 … NN-11. Delivery date fixture: 2026-06-01.
 *
 * The scenario boundaries are the whole point of this file — Day 14/15 and Day 28/29 each flip
 * behaviour, and getting them wrong either invents a visit that should not exist or marks a Sakhi
 * as having missed one she could never have done.
 */
class NnScheduleGeneratorTest {

  private lateinit var generator: NnScheduleGenerator

  private val delivery = LocalDate.of(2026, 6, 1)
  private val createdAt = Instant.parse("2026-06-01T09:00:00Z")

  @Before
  fun setUp() {
    generator = NnScheduleGenerator(HardcodedRuleSource())
  }

  // ---- Scenario A: form filled Day 0–14 --------------------------------------------------------

  @Test
  fun `NN-1 scenario A generates both NN1 and NN2`() {
    val visits = seriesForFormFilledOn(delivery)

    assertEquals(listOf("NN1", "NN2"), visits.map { it.visitCode })
    assertEquals(delivery, visits[0].windowStartDate)
    assertEquals(delivery.plusDays(14), visits[0].windowEndDate)
    assertEquals(delivery.plusDays(15), visits[1].windowStartDate)
    assertEquals(delivery.plusDays(28), visits[1].windowEndDate)
  }

  @Test
  fun `NN-2 scenario A boundary Day 14 still generates both`() {
    assertEquals(2, seriesForFormFilledOn(delivery.plusDays(14)).size)
  }

  @Test
  fun `NN-3 NN2 opens on Day 15 in scenario A`() {
    val nn2 = seriesForFormFilledOn(delivery)[1]

    assertEquals(delivery.plusDays(15), nn2.windowStartDate)
    assertEquals(delivery.plusDays(15), nn2.scheduledDate)
  }

  /**
   * A visit cannot be performed before its delivery form exists, so NN1's nominal Day 0 opening is
   * clamped forward to the fill date. Documented interpretation — the SRS states the window as
   * Day 0–14 without addressing a mid-window fill.
   */
  @Test
  fun `scenario A with a late fill clamps NN1's window open to the fill date`() {
    val filledOn = delivery.plusDays(10)
    val nn1 = seriesForFormFilledOn(filledOn).first()

    assertEquals(filledOn, nn1.windowStartDate)
    assertEquals(delivery.plusDays(14), nn1.windowEndDate)
    assertEquals(filledOn, nn1.scheduledDate)
  }

  // ---- Scenario B: form filled Day 15–27 -------------------------------------------------------

  /**
   * NN-4, and the most important assertion in this file. NN1's window closed before the form
   * arrived, so **no NN1 row exists at all** — it must not appear as MISSED, which would put a
   * failure on the Sakhi's record for a visit that was never possible and would trip the
   * immediate-escalation rule for a missed NN visit.
   */
  @Test
  fun `NN-4 scenario B skips NN1 entirely rather than marking it missed`() {
    val visits = seriesForFormFilledOn(delivery.plusDays(15))

    assertEquals(1, visits.size)
    assertTrue("No NN1 row may exist", visits.none { it.visitCode == "NN1" })
    assertTrue(
      "No row may be pre-marked missed",
      visits.none { it.status == VisitScheduleStatus.MISSED },
    )
  }

  @Test
  fun `NN-5 the single scenario B visit is still called NN2`() {
    val visit = seriesForFormFilledOn(delivery.plusDays(15)).single()

    assertEquals("NN2", visit.visitCode)
    assertEquals(2, visit.sequenceNo)
  }

  @Test
  fun `NN-6 scenario B window runs from the fill date to Day 28`() {
    val filledOn = delivery.plusDays(19)
    val visit = seriesForFormFilledOn(filledOn).single()

    assertEquals(filledOn, visit.windowStartDate)
    assertEquals(delivery.plusDays(28), visit.windowEndDate)
    assertEquals(filledOn, visit.scheduledDate)
  }

  @Test
  fun `NN-7 scenario B boundary Day 27`() {
    val filledOn = delivery.plusDays(27)
    val visit = seriesForFormFilledOn(filledOn).single()

    assertEquals("NN2", visit.visitCode)
    assertEquals(filledOn, visit.windowStartDate)
    assertEquals(delivery.plusDays(28), visit.windowEndDate)
  }

  // ---- Scenario C and beyond -------------------------------------------------------------------

  @Test
  fun `NN-8 scenario C on Day 28 generates NN2 for that single day`() {
    val filledOn = delivery.plusDays(28)
    val visit = seriesForFormFilledOn(filledOn).single()

    assertEquals("NN2", visit.visitCode)
    assertEquals(filledOn, visit.windowStartDate)
    assertEquals(filledOn, visit.windowEndDate)
    assertEquals(filledOn, visit.scheduledDate)
  }

  /**
   * NN-9. Not covered by the SRS — raised with ARMMAN. Generating nothing is the safe reading:
   * inventing a visit outside the neonatal period would be worse than recording none.
   */
  @Test
  fun `NN-9 a form filled after Day 28 generates nothing`() {
    assertTrue(seriesForFormFilledOn(delivery.plusDays(29)).isEmpty())
    assertTrue(seriesForFormFilledOn(delivery.plusDays(60)).isEmpty())
  }

  // ---- Cross-cutting ---------------------------------------------------------------------------

  @Test
  fun `NN-10 NN windows are fixed ranges, never plus-or-minus N`() {
    // A ±5 leak would make NN1 11 days wide and open it before the delivery date.
    val nn1 = seriesForFormFilledOn(delivery).first()

    assertEquals(15, VisitWindow(nn1.windowStartDate, nn1.windowEndDate).lengthInDays)
    assertTrue("NN1 must not open before delivery", !nn1.windowStartDate.isBefore(delivery))
  }

  /** NN-11 / SR-NN-01 — the neonatal phase never produces an HR row. */
  @Test
  fun `NN-11 no HR visit type is ever emitted for the neonatal phase`() {
    listOf(0L, 10L, 15L, 28L).forEach { offset ->
      seriesForFormFilledOn(delivery.plusDays(offset)).forEach {
        assertEquals(VisitCodeType.NN, it.visitType)
      }
    }
    assertTrue(!HardcodedRuleSource().supportsHrVisits(VisitCodeType.NN))
  }

  @Test
  fun `every NN row anchors to the delivery date, not the form fill date`() {
    seriesForFormFilledOn(delivery.plusDays(19)).forEach {
      assertEquals(AnchorType.DELIVERY_DATE, it.anchorType)
      assertEquals(delivery, it.anchorDate)
    }
  }

  @Test
  fun `a missed NN visit escalates immediately`() {
    seriesForFormFilledOn(delivery).forEach {
      assertEquals(EscalationPolicy.IMMEDIATE, it.escalationPolicy)
    }
  }

  @Test
  fun `every scheduled date sits inside its own window across all scenarios`() {
    listOf(0L, 10L, 14L, 15L, 19L, 27L, 28L).forEach { offset ->
      seriesForFormFilledOn(delivery.plusDays(offset)).forEach {
        assertTrue(
          "${it.visitCode} scheduled ${it.scheduledDate} outside its window",
          !it.scheduledDate.isBefore(it.windowStartDate) &&
            !it.scheduledDate.isAfter(it.windowEndDate),
        )
      }
    }
  }

  @Test
  fun `every row carries the rule version, a unique id and GENERATED status`() {
    val visits = seriesForFormFilledOn(delivery)

    assertTrue(visits.all { it.generatedByRuleVersion == HardcodedRuleSource().ruleVersion })
    assertEquals(visits.size, visits.map { it.localScheduleUuid }.distinct().size)
    assertTrue(visits.all { it.status == VisitScheduleStatus.GENERATED })
  }

  @Test
  fun `generation without a delivery form fill date fails loudly`() {
    val result = runCatching {
      generator.generateSeries(
        ScheduleContext(
          localBeneficiaryId = "ben-1",
          registrationDate = delivery,
          deliveryDate = delivery,
        ),
      )
    }
    assertTrue("The scenario split is undecidable without a fill date", result.isFailure)
  }

  private fun seriesForFormFilledOn(filledOn: LocalDate): List<VisitScheduleEntity> {
    var counter = 0
    return generator.generateSeries(
      ScheduleContext(
        localBeneficiaryId = "ben-1",
        registrationDate = delivery,
        deliveryDate = delivery,
        deliveryFormFilledOn = filledOn,
      ),
      newUuid = { "nn-${counter++}" },
      createdAt = createdAt,
    )
  }
}
