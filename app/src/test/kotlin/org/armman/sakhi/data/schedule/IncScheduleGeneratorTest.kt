package org.armman.sakhi.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** CR-022d cases INC-1 … INC-15. DOB fixture: 2026-06-01, so the cutoff is 2027-06-06. */
class IncScheduleGeneratorTest {

  private lateinit var generator: IncScheduleGenerator

  private val dob = LocalDate.of(2026, 6, 1)
  private val cutoff = dob.plusDays(370)
  private val createdAt = Instant.parse("2026-06-10T09:00:00Z")

  @Before
  fun setUp() {
    generator = IncScheduleGenerator(HardcodedRuleSource())
  }

  // ---- Early registration, DOB Day 0–58 --------------------------------------------------------

  @Test
  fun `INC-1 early registration produces eleven visits`() {
    assertEquals(11, series(dob.plusDays(9)).size)
  }

  /** INC-2. The defining property of the early band: INC1 waits for the neonatal period to end. */
  @Test
  fun `INC-2 early registration anchors INC1 to DOB plus 58, not to the registration date`() {
    val registration = dob.plusDays(9)
    val inc1 = series(registration).first()

    assertEquals(dob.plusDays(58), inc1.scheduledDate)
    assertTrue("Must not use the registration date", inc1.scheduledDate != registration)
  }

  @Test
  fun `INC-3 early registration chains every thirty days from INC1`() {
    val visits = series(dob.plusDays(9))

    visits.zipWithNext { previous, next ->
      assertEquals(30L, ChronoUnit.DAYS.between(previous.scheduledDate, next.scheduledDate))
    }
    assertEquals(dob.plusDays(58 + 10 * 30), visits.last().scheduledDate)
  }

  @Test
  fun `INC-4 boundary Day 58 is still early registration`() {
    val visits = series(dob.plusDays(58))

    assertEquals(11, visits.size)
    assertEquals(dob.plusDays(58), visits.first().scheduledDate)
  }

  @Test
  fun `INC-15 early registration anchors to the DOB`() {
    series(dob.plusDays(9)).forEach {
      assertEquals(AnchorType.DOB, it.anchorType)
      assertEquals(dob, it.anchorDate)
    }
  }

  // ---- Late registration, after Day 58 ---------------------------------------------------------

  @Test
  fun `INC-5 boundary Day 59 is late registration`() {
    val registration = dob.plusDays(59)
    assertEquals(registration, series(registration).first().scheduledDate)
  }

  @Test
  fun `INC-6 late registration puts INC1 on the registration date itself`() {
    val registration = LocalDate.of(2026, 9, 1)
    assertEquals(registration, series(registration).first().scheduledDate)
  }

  @Test
  fun `INC-7 late registration at Day 92 produces ten visits`() {
    assertEquals(10, series(LocalDate.of(2026, 9, 1)).size)
  }

  @Test
  fun `INC-8 late registration chains every thirty days from INC1`() {
    val registration = LocalDate.of(2026, 9, 1)
    val visits = series(registration)

    visits.zipWithNext { previous, next ->
      assertEquals(30L, ChronoUnit.DAYS.between(previous.scheduledDate, next.scheduledDate))
    }
    assertEquals(registration.plusDays(9 * 30), visits.last().scheduledDate)
  }

  @Test
  fun `INC-15 late registration anchors to the registration date`() {
    val registration = LocalDate.of(2026, 9, 1)
    series(registration).forEach {
      assertEquals(AnchorType.REGISTRATION, it.anchorType)
      assertEquals(registration, it.anchorDate)
    }
  }

  @Test
  fun `INC-12 registration close to twelve months produces INC1 alone`() {
    val visits = series(dob.plusDays(360))

    assertEquals(1, visits.size)
    assertEquals("INC1", visits.single().visitCode)
  }

  // ---- The DOB + 370 cutoff --------------------------------------------------------------------

  /** INC-10. Day 100 puts the last visit exactly on the cutoff — inclusive, so it survives. */
  @Test
  fun `INC-10 a visit landing exactly on DOB plus 370 is kept`() {
    val visits = series(dob.plusDays(100))

    assertEquals(10, visits.size)
    assertEquals(cutoff, visits.last().scheduledDate)
  }

  /**
   * INC-9 / INC-10. One day later the same visit would land on DOB + 371, so it is dropped. The
   * assertion that matters: nine rows exist, and none of them is marked MISSED — the child aged
   * out, the Sakhi did not fail.
   */
  @Test
  fun `INC-9 a visit past DOB plus 370 is dropped and not marked missed`() {
    val visits = series(dob.plusDays(101))

    assertEquals(9, visits.size)
    assertTrue(visits.none { it.status == VisitScheduleStatus.MISSED })
    assertTrue(visits.all { !it.scheduledDate.isAfter(cutoff) })
  }

  /** INC-11. Dropping the tail must not renumber what remains. */
  @Test
  fun `INC-11 dropping does not renumber the surviving visits`() {
    val visits = series(dob.plusDays(101))

    assertEquals((1..9).toList(), visits.map { it.sequenceNo })
    assertEquals("INC9", visits.last().visitCode)
  }

  @Test
  fun `no INC visit is ever scheduled past the cutoff, across the whole registration range`() {
    (0L..364L step 7).forEach { age ->
      series(dob.plusDays(age)).forEach {
        assertTrue(
          "${it.visitCode} on ${it.scheduledDate} is past the cutoff $cutoff",
          !it.scheduledDate.isAfter(cutoff),
        )
      }
    }
  }

  @Test
  fun `sequence numbers are always contiguous from one`() {
    (0L..364L step 13).forEach { age ->
      val visits = series(dob.plusDays(age))
      assertEquals("age $age", (1..visits.size).toList(), visits.map { it.sequenceNo })
    }
  }

  // ---- Windows and metadata --------------------------------------------------------------------

  @Test
  fun `INC-13 every INC window is symmetric five days`() {
    series(dob.plusDays(9)).forEach {
      assertEquals(it.scheduledDate.minusDays(5), it.windowStartDate)
      assertEquals(it.scheduledDate.plusDays(5), it.windowEndDate)
    }
  }

  @Test
  fun `visit codes agree with sequence numbers`() {
    series(dob.plusDays(9)).forEachIndexed { index, visit ->
      assertEquals("INC${index + 1}", visit.visitCode)
      assertEquals(VisitCodeType.INC, visit.visitType)
    }
  }

  @Test
  fun `two consecutive missed INC visits escalate`() {
    series(dob.plusDays(9)).forEach {
      assertEquals(EscalationPolicy.AFTER_TWO_CONSECUTIVE, it.escalationPolicy)
    }
  }

  @Test
  fun `every row carries the rule version, a unique id and GENERATED status`() {
    val visits = series(dob.plusDays(9))

    assertTrue(visits.all { it.generatedByRuleVersion == HardcodedRuleSource().ruleVersion(VisitCodeType.INC) })
    assertEquals(visits.size, visits.map { it.localScheduleUuid }.distinct().size)
    assertTrue(visits.all { it.status == VisitScheduleStatus.GENERATED })
  }

  @Test
  fun `generating without a DOB fails loudly`() {
    val result = runCatching {
      generator.generateSeries(
        ScheduleContext(localBeneficiaryId = "ben-1", registrationDate = dob),
      )
    }
    assertTrue(result.isFailure)
  }

  // ---- INC-HR, FR-S-3.4 ------------------------------------------------------------------------

  @Test
  fun `INC-14 the HR visit anchors to the actual completion date with a two-day window`() {
    val inc3 = schedule(
      "inc3",
      visitCode = "INC3",
      visitType = VisitCodeType.INC,
      sequenceNo = 3,
      scheduledDate = LocalDate.of(2026, 9, 27),
    )
    val completedOn = LocalDate.of(2026, 10, 2)

    val hr = generator.generateHrVisit(
      context(dob.plusDays(9)),
      inc3,
      completedOn,
      newUuid = { "hr1" },
      createdAt = createdAt,
    )!!

    assertEquals(completedOn.plusDays(15), hr.scheduledDate)
    assertEquals(hr.scheduledDate.minusDays(2), hr.windowStartDate)
    assertEquals(hr.scheduledDate.plusDays(2), hr.windowEndDate)
    assertEquals(AnchorType.ACTUAL_VISIT, hr.anchorType)
    assertEquals("inc3", hr.anchorVisitLocalUuid)
    assertEquals(VisitCodeType.INC_HR, hr.visitType)
  }

  @Test
  fun `a neonatal trigger never produces an INC-HR visit`() {
    val nn = schedule("nn1", visitCode = "NN1", visitType = VisitCodeType.NN)

    assertNull(generator.generateHrVisit(context(dob), nn, LocalDate.of(2026, 6, 10)))
  }

  @Test
  fun `a missed INC-HR visit escalates immediately`() {
    val hr = generator.generateHrVisit(
      context(dob.plusDays(9)),
      schedule("inc3", visitType = VisitCodeType.INC),
      LocalDate.of(2026, 10, 2),
      newUuid = { "hr1" },
    )!!

    assertEquals(EscalationPolicy.IMMEDIATE, hr.escalationPolicy)
  }

  // ---- CCV transition date ---------------------------------------------------------------------

  @Test
  fun `the CCV transition falls on the last generated INC visit`() {
    val visits = series(dob.plusDays(9))

    assertEquals(visits.last().scheduledDate, generator.ccvTransitionDate(dob, visits))
  }

  @Test
  fun `the CCV transition falls back to DOB plus 365 when no INC visit exists`() {
    assertEquals(dob.plusDays(365), generator.ccvTransitionDate(dob, emptyList()))
  }

  @Test
  fun `the CCV transition ignores INC-HR rows`() {
    val visits = series(dob.plusDays(9))
    val strayHr = schedule(
      "hr",
      visitType = VisitCodeType.INC_HR,
      scheduledDate = visits.last().scheduledDate.plusDays(40),
    )

    assertEquals(visits.last().scheduledDate, generator.ccvTransitionDate(dob, visits + strayHr))
  }

  // ---- Seam ------------------------------------------------------------------------------------

  @Test
  fun `the generator honours a cutoff supplied by a fake rule source`() {
    // Fake: INC1 at DOB+20, 3 more every 10 days -> 20, 30, 40, 50; cutoff 35 keeps the first three.
    val fake = IncScheduleGenerator(
      FakeRuleSource(interval = 10, count = 3, cutoff = 35, incFirstVisitOffset = 20, earlyIncMaxDay = 30L),
    )

    val visits = fake.generateSeries(context(dob.plusDays(9)), newUuid = { "id" })

    assertEquals(
      listOf(dob.plusDays(20), dob.plusDays(30)),
      visits.map { it.scheduledDate },
    )
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  private fun context(registration: LocalDate) = ScheduleContext(
    localBeneficiaryId = "ben-1",
    registrationDate = registration,
    dob = dob,
  )

  private fun series(registration: LocalDate): List<VisitScheduleEntity> {
    var counter = 0
    return generator.generateSeries(
      context(registration),
      newUuid = { "inc-${counter++}" },
      createdAt = createdAt,
    )
  }
}
