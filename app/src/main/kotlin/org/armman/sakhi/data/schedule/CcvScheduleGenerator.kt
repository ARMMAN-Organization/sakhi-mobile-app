package org.armman.sakhi.data.schedule

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the Child Care Visits covering 13–24 months (SRS §3A.2.3 "CCV Visit Schedule",
 * the 1000 Days approach).
 *
 * ### Generated at the transition, not at registration
 * The SRS considers and explicitly rejects pre-generating a placeholder CCV schedule at
 * registration: the child's risk state is unknown that far ahead, so the app would have to delete
 * and regenerate six visits at month 12 — *"messy, confusing for the Sakhi, and risky if the app is
 * offline and the replacement doesn't sync cleanly."*
 *
 * So nothing is generated until the INC-to-CCV transition. The projected six-visit count used for
 * budgeting is a dashboard concern and is deliberately **not** implemented here.
 *
 * ### Risk state is a one-time evaluation over the FULL infant phase
 * [determineRiskState] scans every INC visit of the 0–12m period, not just the last three. The SRS
 * mentions the last three only to say they are *"a subset of this scan"* — a phrase easy to read as
 * the opposite of what it means.
 *
 * ### The journey opens with an HR visit
 * *"The 1st visit of the CCV journey will begin with the CCV-HR visit."* True regardless of risk
 * state; what the state changes is the cadence that follows.
 */
@Singleton
class CcvScheduleGenerator @Inject constructor(
  private val rules: ScheduleRuleSource,
) {

  /**
   * The child's opening CCV risk state, from the complete set of INC visit outcomes.
   *
   * Only completed visits carry an outcome, so an empty list means no INC visit was ever done —
   * which is not evidence of good health, but there is nothing to scan, so it reads as
   * [CcvRiskState.NEVER_AT_HR]. Worth revisiting if a child can reach CCV with no infant-phase
   * visits at all.
   */
  fun determineRiskState(incOutcomes: List<IncVisitOutcome>): CcvRiskState {
    if (incOutcomes.isEmpty()) return CcvRiskState.NEVER_AT_HR

    val mostRecent = incOutcomes.maxBy { it.completedOn }
    return when (mostRecent.hrFinding) {
      HrFinding.SAM, HrFinding.DANGER_SIGN -> CcvRiskState.CURRENTLY_HR_SAM_OR_DANGER
      HrFinding.OTHER -> CcvRiskState.CURRENTLY_HR_OTHER
      // Clean at the most recent visit — but the SRS reserves "never at HR" for a clean FULL scan.
      null -> if (incOutcomes.any { it.hrFinding != null }) {
        CcvRiskState.PREVIOUSLY_AT_HR
      } else {
        CcvRiskState.NEVER_AT_HR
      }
    }
  }

  /**
   * The CCV series, generated at [transitionDate] (the last INC visit, or DOB + 365 — see
   * [IncScheduleGenerator.ccvTransitionDate]).
   *
   * The first row is always a `CCV_HR` visit. For a currently-high-risk child it sits 30 days out
   * and the standard chain follows from there; otherwise the chain runs at the two-monthly cadence.
   */
  fun generateSeries(
    context: ScheduleContext,
    transitionDate: LocalDate,
    riskState: CcvRiskState,
    newUuid: () -> String = { UUID.randomUUID().toString() },
    createdAt: Instant = Instant.now(),
  ): List<VisitScheduleEntity> {
    val createdAtMillis = createdAt.toEpochMilli()
    val openingOffset = openingOffsetDays(riskState)
    val interval = rules.intervalDays(VisitCodeType.CCV).toLong()

    val openingDate = transitionDate.plusDays(openingOffset)
    val opening = buildVisit(
      context = context,
      visitType = VisitCodeType.CCV_HR,
      visitCode = "$CCV_HR_CODE_PREFIX$FIRST_SEQUENCE",
      sequenceNo = FIRST_SEQUENCE,
      scheduledDate = openingDate,
      transitionDate = transitionDate,
      newUuid = newUuid,
      createdAtMillis = createdAtMillis,
    )

    // The opening HR visit occupies the first slot in the journey, so the standard chain that
    // follows is one visit shorter than the nominal count.
    val remaining = (rules.visitCount(VisitCodeType.CCV, context) - 1).coerceAtLeast(0)

    var scheduledDate = openingDate
    val rest = (1..remaining).map { index ->
      scheduledDate = scheduledDate.plusDays(interval)
      val sequenceNo = index + 1
      buildVisit(
        context = context,
        visitType = VisitCodeType.CCV,
        visitCode = "$CCV_CODE_PREFIX$sequenceNo",
        sequenceNo = sequenceNo,
        scheduledDate = scheduledDate,
        transitionDate = transitionDate,
        newUuid = newUuid,
        createdAtMillis = createdAtMillis,
      )
    }

    return listOf(opening) + rest
  }

  /**
   * Days from the transition to the journey's opening visit.
   *
   * A currently-high-risk child is seen within the HR interval (30 days); everyone else follows the
   * standard cadence. [CcvRiskState.PREVIOUSLY_AT_HR] takes the standard path — see its doc for the
   * assumption that represents.
   */
  private fun openingOffsetDays(riskState: CcvRiskState): Long = when (riskState) {
    CcvRiskState.CURRENTLY_HR_SAM_OR_DANGER,
    CcvRiskState.CURRENTLY_HR_OTHER,
    -> rules.hrOffsetDays(VisitCodeType.CCV).toLong()

    CcvRiskState.NEVER_AT_HR,
    CcvRiskState.PREVIOUSLY_AT_HR,
    -> rules.intervalDays(VisitCodeType.CCV).toLong()
  }

  private fun buildVisit(
    context: ScheduleContext,
    visitType: VisitCodeType,
    visitCode: String,
    sequenceNo: Int,
    scheduledDate: LocalDate,
    transitionDate: LocalDate,
    newUuid: () -> String,
    createdAtMillis: Long,
  ): VisitScheduleEntity {
    val window = rules.window(visitType, sequenceNo, scheduledDate)
    return VisitScheduleEntity(
      localScheduleUuid = newUuid(),
      localBeneficiaryId = context.localBeneficiaryId,
      visitCode = visitCode,
      visitType = visitType,
      sequenceNo = sequenceNo,
      scheduledDate = scheduledDate,
      windowStartDate = window.start,
      windowEndDate = window.end,
      anchorType = AnchorType.CCV_TRANSITION,
      anchorDate = transitionDate,
      generatedByRuleVersion = rules.ruleVersion(visitType),
      escalationPolicy = rules.escalationPolicy(visitType),
      createdAtEpochMillis = createdAtMillis,
    )
  }

  private companion object {
    const val CCV_CODE_PREFIX = "CCV"
    const val CCV_HR_CODE_PREFIX = "CCV-HR"
    const val FIRST_SEQUENCE = 1
  }
}
