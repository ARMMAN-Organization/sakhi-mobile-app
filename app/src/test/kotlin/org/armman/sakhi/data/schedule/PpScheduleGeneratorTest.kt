package org.armman.sakhi.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** CR-022c cases PP-1 … PP-8. Delivery date fixture: 2026-06-01. */
class PpScheduleGeneratorTest {

  private lateinit var generator: PpScheduleGenerator

  private val delivery = LocalDate.of(2026, 6, 1)
  private val createdAt = Instant.parse("2026-06-01T09:00:00Z")

  @Before
  fun setUp() {
    generator = PpScheduleGenerator(HardcodedRuleSource())
  }

  @Test
  fun `PP-1 five PP visits are generated on delivery form submission`() {
    val visits = series()

    assertEquals(5, visits.size)
    assertEquals(listOf(1, 2, 3, 4, 5), visits.map { it.sequenceNo })
    assertEquals(listOf("PP1", "PP2", "PP3", "PP4", "PP5"), visits.map { it.visitCode })
    assertTrue(visits.all { it.visitType == VisitCodeType.PP })
  }

  /** PP-2. PP5 at +118 is the reconciled reading of the SRS table — see open question Q1. */
  @Test
  fun `PP-2 scheduled dates are delivery plus 0 15 58 88 118`() {
    assertEquals(
      listOf(0L, 15L, 58L, 88L, 118L).map(delivery::plusDays),
      series().map { it.scheduledDate },
    )
  }

  @Test
  fun `PP-3 PP1 window is a fixed range Day 0 to Day 14`() {
    val pp1 = series().first()

    assertEquals(delivery, pp1.windowStartDate)
    assertEquals(delivery.plusDays(14), pp1.windowEndDate)
    // Regression guard: the ±5 default must not leak into PP1.
    assertEquals(15, windowLength(pp1))
  }

  @Test
  fun `PP-4 PP2 window is a fixed range Day 15 to Day 28`() {
    val pp2 = series()[1]

    assertEquals(delivery.plusDays(15), pp2.windowStartDate)
    assertEquals(delivery.plusDays(28), pp2.windowEndDate)
    assertEquals(14, windowLength(pp2))
  }

  /**
   * PP-5. This is the check that makes the Q1 reconciliation self-consistent: PP3–PP5 at ±5 around
   * 58/88/118 reproduce the SRS's own window-close column (63/93/123) exactly.
   */
  @Test
  fun `PP-5 PP3 to PP5 use symmetric windows that reproduce the SRS close dates`() {
    val visits = series()

    listOf(2 to (53L to 63L), 3 to (83L to 93L), 4 to (113L to 123L)).forEach { (index, range) ->
      val (start, end) = range
      assertEquals("PP${index + 1} window start", delivery.plusDays(start), visits[index].windowStartDate)
      assertEquals("PP${index + 1} window end", delivery.plusDays(end), visits[index].windowEndDate)
    }
  }

  @Test
  fun `PP-6 every PP row anchors to the delivery date`() {
    series().forEach {
      assertEquals(AnchorType.DELIVERY_DATE, it.anchorType)
      assertEquals(delivery, it.anchorDate)
    }
  }

  @Test
  fun `PP-7 PP is not generated without a delivery date`() {
    val result = runCatching {
      generator.generateSeries(
        ScheduleContext(localBeneficiaryId = "ben-1", registrationDate = delivery),
      )
    }
    assertTrue("PP at enrolment is a programming error", result.isFailure)
  }

  @Test
  fun `PP-8 only the last PP visit raises the closure prompt`() {
    val visits = series()

    assertTrue(generator.isClosurePromptTrigger(visits.last(), context()))
    visits.dropLast(1).forEach {
      assertFalse("${it.visitCode} must not trigger closure", generator.isClosurePromptTrigger(it, context()))
    }
  }

  @Test
  fun `a non-PP visit never raises the closure prompt`() {
    val anc = schedule("anc10", visitCode = "ANC10", visitType = VisitCodeType.ANC, sequenceNo = 5)
    assertFalse(generator.isClosurePromptTrigger(anc, context()))
  }

  @Test
  fun `every scheduled date sits inside its own window`() {
    series().forEach {
      assertTrue(
        "${it.visitCode} scheduled ${it.scheduledDate} outside [${it.windowStartDate}, ${it.windowEndDate}]",
        !it.scheduledDate.isBefore(it.windowStartDate) && !it.scheduledDate.isAfter(it.windowEndDate),
      )
    }
  }

  @Test
  fun `a missed PP visit escalates immediately`() {
    series().forEach { assertEquals(EscalationPolicy.IMMEDIATE, it.escalationPolicy) }
  }

  @Test
  fun `every row carries the rule version, a unique id and GENERATED status`() {
    val visits = series()

    assertTrue(visits.all { it.generatedByRuleVersion == HardcodedRuleSource().ruleVersion })
    assertEquals(visits.size, visits.map { it.localScheduleUuid }.distinct().size)
    assertTrue(visits.all { it.status == VisitScheduleStatus.GENERATED })
  }

  @Test
  fun `the generator honours an offset table supplied by a fake rule source`() {
    val fake = PpScheduleGenerator(FakeRuleSource(count = 3, offsetTable = listOf(0, 20, 40)))

    val visits = fake.generateSeries(context(), newUuid = { "id" }, createdAt = createdAt)

    assertEquals(
      listOf(delivery, delivery.plusDays(20), delivery.plusDays(40)),
      visits.map { it.scheduledDate },
    )
  }

  private fun context() = ScheduleContext(
    localBeneficiaryId = "ben-1",
    registrationDate = LocalDate.of(2026, 1, 1),
    deliveryDate = delivery,
    deliveryFormFilledOn = delivery,
  )

  private fun series(): List<VisitScheduleEntity> {
    var counter = 0
    return generator.generateSeries(
      context(),
      newUuid = { "pp-${counter++}" },
      createdAt = createdAt,
    )
  }

  private fun windowLength(visit: VisitScheduleEntity) =
    VisitWindow(visit.windowStartDate, visit.windowEndDate).lengthInDays
}
