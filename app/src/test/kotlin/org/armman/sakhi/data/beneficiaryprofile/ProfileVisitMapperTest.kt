package org.armman.sakhi.data.beneficiaryprofile

import org.armman.sakhi.data.schedule.VisitCodeType
import org.armman.sakhi.data.schedule.VisitScheduleStatus
import org.armman.sakhi.data.schedule.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** CR-022f cases PR-4 … PR-8. */
class ProfileVisitMapperTest {

  private val today = LocalDate.of(2026, 8, 4)

  /**
   * PR-4. The regression this guards is the one the design comparison caught: sorting the whole
   * list newest-first put ANC9 — eight months out — at the top, and today's ANC1 at the bottom of
   * nine cards. Upcoming visits must read soonest-first.
   */
  @Test
  fun `upcoming visits render soonest first`() {
    val visits = listOf(
      row("s1", "ANC1", 1, today),
      row("s2", "ANC2", 2, today.plusDays(30)),
      row("s3", "ANC3", 3, today.plusDays(60)),
    ).toProfileVisits(today)

    assertEquals(listOf("ANC1", "ANC2", "ANC3"), visits.map { it.label })
  }

  @Test
  fun `completed history renders newest first, beneath the upcoming visits`() {
    val visits = listOf(
      row("s1", "ANC1", 1, today.minusDays(60), status = VisitScheduleStatus.COMPLETED),
      row("s2", "ANC2", 2, today.minusDays(30), status = VisitScheduleStatus.COMPLETED),
      row("s3", "ANC3", 3, today),
      row("s4", "ANC4", 4, today.plusDays(30)),
    ).toProfileVisits(today)

    // Next visit first, then the rest of the plan, then history newest-first.
    assertEquals(listOf("ANC3", "ANC4", "ANC2", "ANC1"), visits.map { it.label })
  }

  @Test
  fun `a full ten-visit series puts today's visit at the top`() {
    val series = (1..10).map { n ->
      row("s$n", "ANC$n", n, today.plusDays((n - 1) * 30L))
    }

    assertEquals("ANC1", series.toProfileVisits(today).first().label)
  }

  // PR-5
  @Test
  fun `superseded and cancelled visits are hidden`() {
    val visits = listOf(
      row("s1", "ANC1", 1, today),
      row("s2", "ANC2", 2, today.plusDays(30), status = VisitScheduleStatus.SUPERSEDED),
      row("s3", "ANC3", 3, today.plusDays(60), status = VisitScheduleStatus.CANCELLED),
    ).toProfileVisits(today)

    assertEquals(listOf("ANC1"), visits.map { it.label })
  }

  // PR-6
  @Test
  fun `completed visits render as completed with the See Data action`() {
    val visits = listOf(
      row("s1", "ANC1", 1, today.minusDays(30), status = VisitScheduleStatus.COMPLETED),
      row("s2", "ANC2", 2, today),
    ).toProfileVisits(today)

    val completed = visits.single { it.label == "ANC1" }
    assertEquals(ProfileVisitState.COMPLETED, completed.state)
    assertEquals(ProfileVisitAction.SEE_DATA, completed.action)
    assertNull("A completed visit has no countdown", completed.daysRemaining)

    val open = visits.single { it.label == "ANC2" }
    assertEquals(ProfileVisitState.OPEN, open.state)
    assertEquals(ProfileVisitAction.START_VISIT, open.action)
  }

  /**
   * PR-7. The deadline is when the window shuts, not the scheduled date. A visit scheduled
   * yesterday with three days of window left is not overdue — measuring to the scheduled date
   * would wrongly show it as such.
   */
  @Test
  fun `days remaining counts to the window close, not the scheduled date`() {
    val visit = row(
      "s1",
      "ANC3",
      3,
      scheduledDate = today.minusDays(1),
      windowStart = today.minusDays(6),
      windowEnd = today.plusDays(4),
    ).let { listOf(it).toProfileVisits(today).single() }

    assertEquals(4, visit.daysRemaining)
  }

  /**
   * A visit whose window has closed shows no countdown. "0 days remaining" reads as "the window
   * shuts today" rather than "it already shut" — false urgency on a row she cannot act on.
   */
  @Test
  fun `a visit whose window has closed shows no countdown`() {
    val visit = listOf(
      row("s1", "ANC1", 1, today.minusDays(20), windowEnd = today.minusDays(10)),
    ).toProfileVisits(today).single()

    assertNull(visit.daysRemaining)
  }

  /**
   * The noise this removes: a freshly generated ANC series spans nine months, and labelling every
   * visit "245 days remaining" buries the one card that matters.
   */
  @Test
  fun `a visit far beyond its window start shows no countdown`() {
    val visit = listOf(
      row(
        "s1",
        "ANC9",
        9,
        today.plusDays(240),
        windowStart = today.plusDays(235),
        windowEnd = today.plusDays(245),
      ),
    ).toProfileVisits(today).single()

    assertNull(visit.daysRemaining)
  }

  /** The design shows "1 day remaining" while Start Visit is still greyed out — a heads-up. */
  @Test
  fun `a visit about to open counts down to its opening day`() {
    val visit = listOf(
      row(
        "s1",
        "ANC2",
        2,
        today.plusDays(6),
        windowStart = today.plusDays(1),
        windowEnd = today.plusDays(11),
      ),
    ).toProfileVisits(today).single()

    assertEquals(1, visit.daysRemaining)
    assertFalse("Not yet startable, but still worth flagging", visit.startable)
  }

  @Test
  fun `the countdown threshold is seven days`() {
    fun badgeAt(daysToOpen: Long) = listOf(
      row(
        "s1",
        "ANC2",
        2,
        today.plusDays(daysToOpen + 5),
        windowStart = today.plusDays(daysToOpen),
        windowEnd = today.plusDays(daysToOpen + 10),
      ),
    ).toProfileVisits(today).single().daysRemaining

    assertEquals(7, badgeAt(7))
    assertNull(badgeAt(8))
  }

  // ---- Start Visit gating ----------------------------------------------------------------------

  @Test
  fun `a visit is startable only inside its window`() {
    val open = listOf(
      row("s1", "ANC1", 1, today, windowStart = today.minusDays(5), windowEnd = today.plusDays(5)),
    ).toProfileVisits(today).single()

    assertTrue(open.startable)
  }

  @Test
  fun `a visit whose window has not opened is not startable`() {
    val future = listOf(
      row(
        "s1",
        "ANC2",
        2,
        today.plusDays(30),
        windowStart = today.plusDays(25),
        windowEnd = today.plusDays(35),
      ),
    ).toProfileVisits(today).single()

    assertFalse(future.startable)
  }

  @Test
  fun `a visit whose window has closed is not startable`() {
    val past = listOf(
      row("s1", "ANC1", 1, today.minusDays(20), windowEnd = today.minusDays(10)),
    ).toProfileVisits(today).single()

    assertFalse(past.startable)
  }

  @Test
  fun `the window is inclusive at both ends`() {
    val onOpeningDay = listOf(
      row("s1", "ANC1", 1, today, windowStart = today, windowEnd = today.plusDays(5)),
    ).toProfileVisits(today).single()
    val onClosingDay = listOf(
      row("s2", "ANC1", 1, today.minusDays(5), windowStart = today.minusDays(5), windowEnd = today),
    ).toProfileVisits(today).single()

    assertTrue(onOpeningDay.startable)
    assertTrue(onClosingDay.startable)
  }

  // ---- FR-S-4.6 pre-visit history --------------------------------------------------------------

  @Test
  fun `the first visit has no pre-visit history`() {
    val visits = listOf(
      row("s1", "ANC1", 1, today),
      row("s2", "ANC2", 2, today.plusDays(30)),
    ).toProfileVisits(today)

    assertTrue(visits.none { it.hasPreVisitHistory })
  }

  @Test
  fun `a visit after a completed one has pre-visit history`() {
    val visits = listOf(
      row("s1", "ANC1", 1, today.minusDays(30), status = VisitScheduleStatus.COMPLETED),
      row("s2", "ANC2", 2, today),
    ).toProfileVisits(today)

    assertFalse(visits.single { it.label == "ANC1" }.hasPreVisitHistory)
    assertTrue(visits.single { it.label == "ANC2" }.hasPreVisitHistory)
  }

  /** Only *completed* visits count — a skipped or still-open earlier visit has no data to show. */
  @Test
  fun `an earlier visit that was never completed does not count as history`() {
    val visits = listOf(
      row("s1", "ANC1", 1, today.minusDays(30), status = VisitScheduleStatus.MISSED),
      row("s2", "ANC2", 2, today),
    ).toProfileVisits(today)

    assertFalse(visits.single { it.label == "ANC2" }.hasPreVisitHistory)
  }

  // ---- Identity and formatting -----------------------------------------------------------------

  /** PR-8. The id must be the schedule's own key, so the visit form receives something the server
   * will recognise once the schedule has synced. Replaces the old "v1".."v4" space (CR-025). */
  @Test
  fun `the profile visit id is the schedule local uuid`() {
    val visit = listOf(row("sched-uuid-1", "ANC1", 1, today)).toProfileVisits(today).single()

    assertEquals("sched-uuid-1", visit.id)
  }

  @Test
  fun `the label is the visit code the Sakhi sees`() {
    val visits = listOf(
      row("s1", "ANC1", 1, today),
      row("s2", "PP3", 3, today.plusDays(30), visitType = VisitCodeType.PP),
      row("s3", "NN2", 2, today.plusDays(60), visitType = VisitCodeType.NN),
    ).toProfileVisits(today)

    assertEquals(listOf("ANC1", "PP3", "NN2"), visits.map { it.label })
  }

  @Test
  fun `the date label is formatted for display`() {
    val visit = listOf(
      row("s1", "ANC1", 1, LocalDate.of(2026, 8, 4)),
    ).toProfileVisits(today).single()

    assertEquals("4 Aug 2026", visit.dateLabel)
  }

  /**
   * A missed visit must not show "0 days remaining" — that reads as "the window shuts today"
   * rather than "it already shut", which is false urgency on a row the Sakhi cannot act on.
   */
  @Test
  fun `a missed visit shows no countdown and is not startable`() {
    val visit = listOf(
      row("s1", "ANC1", 1, today.minusDays(20), status = VisitScheduleStatus.MISSED),
    ).toProfileVisits(today).single()

    assertNull(visit.daysRemaining)
    assertFalse(visit.startable)
  }

  @Test
  fun `an empty schedule maps to an empty list`() {
    assertTrue(emptyList<org.armman.sakhi.data.schedule.VisitScheduleEntity>().toProfileVisits(today).isEmpty())
  }

  @Test
  fun `a schedule of only retired rows maps to an empty list`() {
    val visits = listOf(
      row("s1", "ANC1", 1, today, status = VisitScheduleStatus.CANCELLED),
      row("s2", "ANC2", 2, today.plusDays(30), status = VisitScheduleStatus.SUPERSEDED),
    ).toProfileVisits(today)

    assertTrue(visits.isEmpty())
  }

  private fun row(
    id: String,
    visitCode: String,
    sequenceNo: Int,
    scheduledDate: LocalDate,
    windowStart: LocalDate = scheduledDate.minusDays(5),
    windowEnd: LocalDate = scheduledDate.plusDays(5),
    status: VisitScheduleStatus = VisitScheduleStatus.GENERATED,
    visitType: VisitCodeType = VisitCodeType.ANC,
  ) = schedule(
    localScheduleUuid = id,
    visitCode = visitCode,
    visitType = visitType,
    sequenceNo = sequenceNo,
    scheduledDate = scheduledDate,
    windowStartDate = windowStart,
    windowEndDate = windowEnd,
    status = status,
  )
}
