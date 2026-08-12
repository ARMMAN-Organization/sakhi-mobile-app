package org.armman.sakhi.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** CR-022d cases CCV-1 … CCV-8. Transition fixture: 2027-05-30 (DOB 2026-06-01 + 363). */
class CcvScheduleGeneratorTest {

  private lateinit var generator: CcvScheduleGenerator
  private lateinit var incGenerator: IncScheduleGenerator

  private val dob = LocalDate.of(2026, 6, 1)
  private val transition = LocalDate.of(2027, 5, 30)
  private val createdAt = Instant.parse("2027-05-30T09:00:00Z")

  @Before
  fun setUp() {
    val rules = HardcodedRuleSource()
    generator = CcvScheduleGenerator(rules)
    incGenerator = IncScheduleGenerator(rules)
  }

  // ---- CCV-1 / CCV-2: when the schedule comes into existence -----------------------------------

  /**
   * CCV-1. The SRS considers and rejects pre-generating a placeholder schedule at registration,
   * because it would have to be deleted and rebuilt at month 12. The guard is structural — there is
   * no code path that produces CCV rows from a registration, only from a transition.
   */
  @Test
  fun `CCV-1 registering a child produces no CCV rows`() {
    val incVisits = incGenerator.generateSeries(
      ScheduleContext(localBeneficiaryId = "ben-1", registrationDate = dob.plusDays(10), dob = dob),
      newUuid = { "inc" },
      createdAt = createdAt,
    )

    assertTrue(incVisits.none { it.visitType == VisitCodeType.CCV || it.visitType == VisitCodeType.CCV_HR })
  }

  @Test
  fun `CCV-2 the schedule is generated at the transition`() {
    assertTrue(series(CcvRiskState.NEVER_AT_HR).isNotEmpty())
  }

  // ---- Cadence by risk state -------------------------------------------------------------------

  @Test
  fun `CCV-3 a never-at-HR child follows the two-monthly cadence`() {
    val visits = series(CcvRiskState.NEVER_AT_HR)

    assertEquals(transition.plusDays(60), visits.first().scheduledDate)
    visits.zipWithNext { previous, next ->
      assertEquals(60L, ChronoUnit.DAYS.between(previous.scheduledDate, next.scheduledDate))
    }
  }

  @Test
  fun `CCV-4 a child with SAM or a danger sign opens with a thirty-day HR visit`() {
    val visits = series(CcvRiskState.CURRENTLY_HR_SAM_OR_DANGER)

    assertEquals(transition.plusDays(30), visits.first().scheduledDate)
    assertEquals(VisitCodeType.CCV_HR, visits.first().visitType)
  }

  @Test
  fun `CCV-5 a child with another current HR condition also opens at thirty days`() {
    assertEquals(transition.plusDays(30), series(CcvRiskState.CURRENTLY_HR_OTHER).first().scheduledDate)
  }

  /** The 30-day CCV cadence is its own constant — reusing ANC/INC's 15 would halve it. */
  @Test
  fun `the CCV high-risk interval is thirty days, not the fifteen used by ANC and INC`() {
    val ccvOpening = series(CcvRiskState.CURRENTLY_HR_OTHER).first().scheduledDate

    assertEquals(transition.plusDays(30), ccvOpening)
    assertTrue("Must not borrow the ANC/INC offset", ccvOpening != transition.plusDays(15))
  }

  // ---- CCV-6: risk state comes from a FULL scan ------------------------------------------------

  /**
   * CCV-6, and the reading most easily got wrong. The SRS says the last three visits are *"a subset
   * of this scan"* — meaning the scan covers the whole 0–12m period. A condition at INC2 with three
   * clean visits since must therefore NOT read as "never at HR".
   */
  @Test
  fun `CCV-6 an HR condition early in the infant phase rules out never-at-HR`() {
    val outcomes = listOf(
      outcome("inc1", dob.plusDays(58)),
      outcome("inc2", dob.plusDays(88), HrFinding.OTHER),
      outcome("inc3", dob.plusDays(118)),
      outcome("inc4", dob.plusDays(148)),
      outcome("inc5", dob.plusDays(178)),
    )

    val state = generator.determineRiskState(outcomes)

    assertTrue("Must not be NEVER_AT_HR", state != CcvRiskState.NEVER_AT_HR)
    assertEquals(CcvRiskState.PREVIOUSLY_AT_HR, state)
  }

  @Test
  fun `a clean full scan reads as never at HR`() {
    val outcomes = (1..5).map { outcome("inc$it", dob.plusDays(58L + it * 30)) }

    assertEquals(CcvRiskState.NEVER_AT_HR, generator.determineRiskState(outcomes))
  }

  @Test
  fun `SAM at the most recent visit outranks a clean history`() {
    val outcomes = listOf(
      outcome("inc1", dob.plusDays(58)),
      outcome("inc2", dob.plusDays(88), HrFinding.SAM),
    )

    assertEquals(CcvRiskState.CURRENTLY_HR_SAM_OR_DANGER, generator.determineRiskState(outcomes))
  }

  @Test
  fun `a danger sign is graded with SAM, not with other HR`() {
    val outcomes = listOf(outcome("inc1", dob.plusDays(58), HrFinding.DANGER_SIGN))

    assertEquals(CcvRiskState.CURRENTLY_HR_SAM_OR_DANGER, generator.determineRiskState(outcomes))
  }

  @Test
  fun `other HR at the most recent visit counts even when previously triggered`() {
    val outcomes = listOf(
      outcome("inc1", dob.plusDays(58), HrFinding.OTHER),
      outcome("inc2", dob.plusDays(88), HrFinding.OTHER),
    )

    assertEquals(CcvRiskState.CURRENTLY_HR_OTHER, generator.determineRiskState(outcomes))
  }

  /** "Most recent" is by completion date, not list order — visits can sync out of order. */
  @Test
  fun `most recent is decided by completion date, not list position`() {
    val outcomes = listOf(
      outcome("inc2", dob.plusDays(88), HrFinding.SAM),
      outcome("inc3", dob.plusDays(118)),
    )

    assertEquals(CcvRiskState.PREVIOUSLY_AT_HR, generator.determineRiskState(outcomes))
  }

  @Test
  fun `no infant-phase visits reads as never at HR`() {
    assertEquals(CcvRiskState.NEVER_AT_HR, generator.determineRiskState(emptyList()))
  }

  /** PREVIOUSLY_AT_HR is an assumption, not an SRS state — pinned so a ruling surfaces as a fail. */
  @Test
  fun `a previously-at-HR child takes the standard cadence pending ARMMAN confirmation`() {
    val visits = series(CcvRiskState.PREVIOUSLY_AT_HR)

    assertEquals(transition.plusDays(60), visits.first().scheduledDate)
  }

  // ---- CCV-7 / CCV-8 ---------------------------------------------------------------------------

  @Test
  fun `CCV-7 the journey opens with a CCV-HR visit in every risk state`() {
    CcvRiskState.entries.forEach { state ->
      val first = series(state).first()
      assertEquals("$state", VisitCodeType.CCV_HR, first.visitType)
      assertEquals("CCV-HR1", first.visitCode)
    }
  }

  @Test
  fun `the visits after the opening one are regular CCV rows`() {
    val visits = series(CcvRiskState.NEVER_AT_HR)

    visits.drop(1).forEach { assertEquals(VisitCodeType.CCV, it.visitType) }
    assertEquals(listOf("CCV2", "CCV3", "CCV4", "CCV5", "CCV6"), visits.drop(1).map { it.visitCode })
  }

  @Test
  fun `CCV-8 the risk state is applied once and the series does not re-evaluate`() {
    // Two calls with the same state produce identical schedules; nothing is derived from a clock
    // or from mutable state between visits.
    assertEquals(
      series(CcvRiskState.NEVER_AT_HR).map { it.scheduledDate },
      series(CcvRiskState.NEVER_AT_HR).map { it.scheduledDate },
    )
  }

  @Test
  fun `six visits are generated in total`() {
    CcvRiskState.entries.forEach { assertEquals("$it", 6, series(it).size) }
  }

  @Test
  fun `every CCV row anchors to the transition date`() {
    series(CcvRiskState.NEVER_AT_HR).forEach {
      assertEquals(AnchorType.CCV_TRANSITION, it.anchorType)
      assertEquals(transition, it.anchorDate)
    }
  }

  @Test
  fun `every row carries the rule version, a unique id and GENERATED status`() {
    val visits = series(CcvRiskState.NEVER_AT_HR)

    assertTrue(visits.all { it.generatedByRuleVersion == HardcodedRuleSource().ruleVersion(VisitCodeType.CCV) })
    assertEquals(visits.size, visits.map { it.localScheduleUuid }.distinct().size)
    assertTrue(visits.all { it.status == VisitScheduleStatus.GENERATED })
  }

  @Test
  fun `sequence numbers are contiguous from one across the whole journey`() {
    assertEquals((1..6).toList(), series(CcvRiskState.NEVER_AT_HR).map { it.sequenceNo })
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  private fun outcome(id: String, completedOn: LocalDate, finding: HrFinding? = null) =
    IncVisitOutcome(scheduleLocalUuid = id, completedOn = completedOn, hrFinding = finding)

  private fun series(riskState: CcvRiskState): List<VisitScheduleEntity> {
    var counter = 0
    return generator.generateSeries(
      ScheduleContext(localBeneficiaryId = "ben-1", registrationDate = dob.plusDays(10), dob = dob),
      transitionDate = transition,
      riskState = riskState,
      newUuid = { "ccv-${counter++}" },
      createdAt = createdAt,
    )
  }
}
