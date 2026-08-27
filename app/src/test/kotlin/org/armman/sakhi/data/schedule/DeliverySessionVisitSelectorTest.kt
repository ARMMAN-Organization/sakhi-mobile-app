package org.armman.sakhi.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * CR-042 (Delivery Event Session). Companion to [NnScheduleGeneratorTest] — that file proves what
 * [NnScheduleGenerator] generates per scenario; this proves which one of those rows the session
 * should open immediately, in [sameSessionNnVisit].
 *
 * Same delivery-date fixture and helper shape as [NnScheduleGeneratorTest] so the two files read
 * as a pair.
 */
class DeliverySessionVisitSelectorTest {

  private val generator = NnScheduleGenerator(HardcodedRuleSource())
  private val delivery = LocalDate.of(2026, 6, 1)
  private val createdAt = Instant.parse("2026-06-01T09:00:00Z")

  @Test
  fun `scenario A same-day fill opens NN1, not NN2`() {
    val nn1 = seriesForFormFilledOn(delivery)

    val sessionVisit = sameSessionNnVisit(nn1, deliveryFormFilledOn = delivery)

    assertEquals("NN1", sessionVisit?.visitCode)
  }

  @Test
  fun `scenario A late fill within the window still opens NN1 at the clamped date`() {
    val filledOn = delivery.plusDays(10)
    val series = seriesForFormFilledOn(filledOn)

    val sessionVisit = sameSessionNnVisit(series, deliveryFormFilledOn = filledOn)

    assertEquals("NN1", sessionVisit?.visitCode)
  }

  @Test
  fun `scenario B fill opens NN2, since NN1 was never generated`() {
    val filledOn = delivery.plusDays(20)
    val series = seriesForFormFilledOn(filledOn)

    val sessionVisit = sameSessionNnVisit(series, deliveryFormFilledOn = filledOn)

    assertEquals("NN2", sessionVisit?.visitCode)
  }

  @Test
  fun `scenario C boundary Day 28 opens NN2`() {
    val filledOn = delivery.plusDays(28)
    val series = seriesForFormFilledOn(filledOn)

    val sessionVisit = sameSessionNnVisit(series, deliveryFormFilledOn = filledOn)

    assertEquals("NN2", sessionVisit?.visitCode)
  }

  @Test
  fun `form filled after Day 28 opens nothing`() {
    val filledOn = delivery.plusDays(29)
    val series = seriesForFormFilledOn(filledOn)

    assertEquals(emptyList<VisitScheduleEntity>(), series)
    assertNull(sameSessionNnVisit(series, deliveryFormFilledOn = filledOn))
  }

  @Test
  fun `an empty series (defensive) opens nothing rather than throwing`() {
    assertNull(sameSessionNnVisit(emptyList(), deliveryFormFilledOn = delivery))
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
