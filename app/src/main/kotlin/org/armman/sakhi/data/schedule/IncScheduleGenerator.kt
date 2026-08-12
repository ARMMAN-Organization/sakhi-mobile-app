package org.armman.sakhi.data.schedule

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the infant care visits covering 0–12 months, on the device, at child registration
 * (SRS §3A.2.3 "INC Visit Schedule").
 *
 * ### Two formulas, one series
 * Which applies depends on how old the child was at registration:
 *
 * | Band | INC1 lands on | Additional visits |
 * |---|---|---|
 * | Early — DOB Day 0–58 | DOB + 58 (end of the neonatal period) | `Round((365 − 58) / 30)` = 10 |
 * | Late — after Day 58 | the registration date itself | `Round((365 − age) / 30)` |
 *
 * Both counts are *additional to INC1*, which is how the SRS words the late-registration formula
 * ("the number of additional visits after INC1") and what makes the early band produce INC1…INC11.
 *
 * ### The cutoff drops, it does not miss
 * Any visit that would fall beyond DOB + 370 is **not generated at all** — not written and later
 * marked MISSED. A dropped visit is one the child aged out of, not one the Sakhi failed to do, and
 * writing it as missed would trip the escalation rule. Surviving visits keep their original
 * sequence numbers; the series is not renumbered to close the gap.
 */
@Singleton
class IncScheduleGenerator @Inject constructor(
  private val rules: ScheduleRuleSource,
) {

  /**
   * The INC series. Requires [ScheduleContext.dob].
   *
   * Returns an empty list when even INC1 falls past the cutoff — a child registered far too late
   * for the infant phase goes straight to CCV rather than receiving a schedule of dead visits.
   */
  fun generateSeries(
    context: ScheduleContext,
    newUuid: () -> String = { UUID.randomUUID().toString() },
    createdAt: Instant = Instant.now(),
  ): List<VisitScheduleEntity> {
    val dob = requireNotNull(context.dob) { "INC schedule requires a date of birth" }

    val isEarly = rules.isEarlyIncRegistration(dob, context.registrationDate)
    val firstVisitDate = if (isEarly) {
      dob.plusDays(rules.incFirstVisitOffsetDays().toLong())
    } else {
      context.registrationDate
    }

    // The rule source returns the count of visits AFTER the first, so the series is 1 + that.
    val additionalVisits = rules.visitCount(VisitCodeType.INC, context)
    val interval = rules.intervalDays(VisitCodeType.INC).toLong()
    val lastAllowedDate = rules.cutoffDays(VisitCodeType.INC)?.let { dob.plusDays(it.toLong()) }
    val createdAtMillis = createdAt.toEpochMilli()

    val anchorType = if (isEarly) AnchorType.DOB else AnchorType.REGISTRATION
    val anchorDate = if (isEarly) dob else context.registrationDate

    var scheduledDate = firstVisitDate
    return buildList {
      (1..additionalVisits + 1).forEach { sequenceNo ->
        if (sequenceNo > 1) scheduledDate = scheduledDate.plusDays(interval)

        // Dropped, not missed — and no `return@forEach` short-circuit chain: later visits are all
        // further out, so the first breach ends the series.
        if (lastAllowedDate != null && scheduledDate.isAfter(lastAllowedDate)) return@buildList

        val window = rules.window(VisitCodeType.INC, sequenceNo, scheduledDate)
        add(
          VisitScheduleEntity(
            localScheduleUuid = newUuid(),
            localBeneficiaryId = context.localBeneficiaryId,
            visitCode = "$INC_CODE_PREFIX$sequenceNo",
            visitType = VisitCodeType.INC,
            sequenceNo = sequenceNo,
            scheduledDate = scheduledDate,
            windowStartDate = window.start,
            windowEndDate = window.end,
            anchorType = anchorType,
            anchorDate = anchorDate,
            generatedByRuleVersion = rules.ruleVersion(VisitCodeType.INC),
            escalationPolicy = rules.escalationPolicy(VisitCodeType.INC),
            createdAtEpochMillis = createdAtMillis,
          ),
        )
      }
    }
  }

  /**
   * An INC high-risk follow-up — same rule as ANC-HR (FR-S-3.4): anchored to the **actual**
   * completion date of the triggering visit, 15 days out, ±2 window.
   *
   * Unlike the regular series this is not subject to the DOB + 370 cutoff: a high-risk finding late
   * in the infant phase still needs following up, and the CCV transition handles anything beyond.
   * Flagged as an interpretation — the SRS states the cutoff for the regular chain only.
   */
  fun generateHrVisit(
    context: ScheduleContext,
    triggeringVisit: VisitScheduleEntity,
    actualCompletionDate: LocalDate,
    existingHrCount: Int = 0,
    newUuid: () -> String = { UUID.randomUUID().toString() },
    createdAt: Instant = Instant.now(),
  ): VisitScheduleEntity? {
    if (!rules.supportsHrVisits(triggeringVisit.visitType)) return null

    val sequenceNo = existingHrCount + 1
    val scheduledDate = actualCompletionDate.plusDays(
      rules.hrOffsetDays(VisitCodeType.INC).toLong(),
    )
    val window = rules.window(VisitCodeType.INC_HR, sequenceNo, scheduledDate)

    return VisitScheduleEntity(
      localScheduleUuid = newUuid(),
      localBeneficiaryId = context.localBeneficiaryId,
      visitCode = "$INC_HR_CODE_PREFIX$sequenceNo",
      visitType = VisitCodeType.INC_HR,
      sequenceNo = sequenceNo,
      scheduledDate = scheduledDate,
      windowStartDate = window.start,
      windowEndDate = window.end,
      anchorType = AnchorType.ACTUAL_VISIT,
      anchorDate = actualCompletionDate,
      anchorVisitLocalUuid = triggeringVisit.localScheduleUuid,
      generatedByRuleVersion = rules.ruleVersion(VisitCodeType.INC_HR),
      escalationPolicy = rules.escalationPolicy(VisitCodeType.INC_HR),
      createdAtEpochMillis = createdAt.toEpochMilli(),
    )
  }

  /**
   * The date the infant phase ends and CCV begins — the last generated INC visit, or DOB + 365 when
   * no INC visit was generated at all.
   *
   * Used by [CcvScheduleGenerator] to decide when to run. Derived from the series rather than
   * assumed, so a change to the INC formulas moves the transition with it.
   */
  fun ccvTransitionDate(dob: LocalDate, incVisits: List<VisitScheduleEntity>): LocalDate =
    incVisits.filter { it.visitType == VisitCodeType.INC }
      .maxByOrNull { it.scheduledDate }
      ?.scheduledDate
      ?: dob.plusDays(rules.incPhaseEndDays().toLong())

  private companion object {
    const val INC_CODE_PREFIX = "INC"
    const val INC_HR_CODE_PREFIX = "INC-HR"
  }
}
