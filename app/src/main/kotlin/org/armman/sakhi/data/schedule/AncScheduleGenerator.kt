package org.armman.sakhi.data.schedule

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the ANC visit series for a pregnant woman, on the device, at enrolment
 * (SRS FR-S-3.1 … FR-S-3.7, SR-ANC-01).
 *
 * ### A pure function
 * No Room, no network, no `LocalDate.now()` inside the maths — everything comes from the
 * [ScheduleContext] passed in, and the two impure inputs ([newUuid], [createdAt]) are defaulted
 * parameters on each entry point, following `EnrollmentRecordMapper`'s convention. That is what
 * lets every SRS rule be pinned by a deterministic unit test.
 *
 * ### It knows no numbers
 * Every interval, count, window width and offset comes from [ScheduleRuleSource]. This class
 * contains no scheduling constant, which is what makes the M3 GoRules swap (CR-032) a Hilt binding
 * change rather than a rewrite. `ScheduleRuleSourceSeamTest` enforces it.
 *
 * ### Three entry points, because the SRS generates ANC rows at three different times
 *  - [generateSeries] — at enrolment, the whole regular ANC chain.
 *  - [generatePostEddVisit] — later, only if no delivery form exists by EDD + 7 (SR-ANC-01).
 *  - [generateHrVisit] — on demand, when a visit is completed and a high-risk condition is found.
 */
@Singleton
class AncScheduleGenerator @Inject constructor(
  private val rules: ScheduleRuleSource,
) {

  /**
   * The regular ANC chain, generated at enrolment.
   *
   * ANC1 falls on the registration date itself with a one-sided `Day 0 → Day +5` window
   * (FR-S-3.2); ANC2 onwards repeat every 30 days with a symmetric ±5 window (FR-S-3.3).
   *
   * Each visit is chained from the **previous scheduled date**, not recomputed as
   * `registration + n × interval`. Both give the same answer today, but chaining is what the SRS
   * describes ("every 30 days from the previous scheduled date") and it is the form that stays
   * correct if a future rule makes an interval vary by position.
   *
   * Requires [ScheduleContext.edd]; the count formula is meaningless without it.
   */
  fun generateSeries(
    context: ScheduleContext,
    newUuid: () -> String = { UUID.randomUUID().toString() },
    createdAt: Instant = Instant.now(),
  ): List<VisitScheduleEntity> {
    requireNotNull(context.edd) { "ANC schedule requires an EDD" }

    val count = rules.visitCount(VisitCodeType.ANC, context)
    val interval = rules.intervalDays(VisitCodeType.ANC).toLong()
    val createdAtMillis = createdAt.toEpochMilli()

    var scheduledDate = context.registrationDate
    return (1..count).map { sequenceNo ->
      if (sequenceNo > 1) scheduledDate = scheduledDate.plusDays(interval)
      val window = rules.window(VisitCodeType.ANC, sequenceNo, scheduledDate)

      VisitScheduleEntity(
        localScheduleUuid = newUuid(),
        localBeneficiaryId = context.localBeneficiaryId,
        visitCode = "$ANC_CODE_PREFIX$sequenceNo",
        visitType = VisitCodeType.ANC,
        sequenceNo = sequenceNo,
        scheduledDate = scheduledDate,
        windowStartDate = window.start,
        windowEndDate = window.end,
        anchorType = AnchorType.REGISTRATION,
        anchorDate = context.registrationDate,
        generatedByRuleVersion = rules.ruleVersion,
        escalationPolicy = rules.escalationPolicy(VisitCodeType.ANC),
        createdAtEpochMillis = createdAtMillis,
      )
    }
  }

  /**
   * SR-ANC-01 — the post-EDD visit.
   *
   * If the delivery form has not been filled by EDD + 7, one additional visit is generated on
   * EDD + 8 with a one-sided EDD+8 → EDD+13 window.
   *
   * Its name continues the regular sequence: `ANC(regular count + 1)`. With 8 regular ANC visits it
   * is ANC9; with 10 it is ANC11. [regularAncCount] must therefore be the count of **regular** ANC
   * rows only — HR rows do not advance the numbering.
   *
   * Returns null when the visit is not due: the delivery form arrived in time, or [asOf] has not
   * yet reached the trigger point. Returning null rather than an empty list makes "one visit or
   * none" explicit at the call site.
   *
   * @param deliveryFormFilledOn null when no delivery form has been submitted.
   * @param asOf the evaluation date — defaulted so callers can pass a real "today" and tests cannot
   *   accidentally depend on the wall clock.
   */
  fun generatePostEddVisit(
    context: ScheduleContext,
    regularAncCount: Int,
    deliveryFormFilledOn: LocalDate?,
    asOf: LocalDate,
    newUuid: () -> String = { UUID.randomUUID().toString() },
    createdAt: Instant = Instant.now(),
  ): VisitScheduleEntity? {
    val edd = requireNotNull(context.edd) { "Post-EDD visit requires an EDD" }

    val graceEnds = edd.plusDays(rules.postEddGraceDays().toLong())
    // Filled on the grace day itself still counts as "by EDD + 7".
    if (deliveryFormFilledOn != null && !deliveryFormFilledOn.isAfter(graceEnds)) return null

    val scheduledDate = edd.plusDays(rules.postEddOffsetDays().toLong())
    if (asOf.isBefore(scheduledDate)) return null

    val window = rules.window(VisitCodeType.ANC_POST_EDD, POST_EDD_SEQUENCE_NO, scheduledDate)

    return VisitScheduleEntity(
      localScheduleUuid = newUuid(),
      localBeneficiaryId = context.localBeneficiaryId,
      // Continues the regular ANC numbering — ANC9, ANC11 — not a separate "post-EDD" label.
      visitCode = "$ANC_CODE_PREFIX${regularAncCount + 1}",
      visitType = VisitCodeType.ANC_POST_EDD,
      sequenceNo = POST_EDD_SEQUENCE_NO,
      scheduledDate = scheduledDate,
      windowStartDate = window.start,
      windowEndDate = window.end,
      anchorType = AnchorType.EDD,
      anchorDate = edd,
      generatedByRuleVersion = rules.ruleVersion,
      escalationPolicy = rules.escalationPolicy(VisitCodeType.ANC_POST_EDD),
      createdAtEpochMillis = createdAt.toEpochMilli(),
    )
  }

  /**
   * FR-S-3.4 — a high-risk follow-up.
   *
   * **Anchored to [actualCompletionDate], never to the triggering visit's scheduled date.** A visit
   * completed five days late pushes its HR follow-up five days later too. This is the rule most
   * easily misread in the SRS, and getting it wrong sends a Sakhi to a high-risk woman on the wrong
   * day, so the anchor is recorded explicitly on the row ([AnchorType.ACTUAL_VISIT] plus
   * [VisitScheduleEntity.anchorVisitLocalUuid]) rather than left implicit.
   *
   * Does not disturb the regular chain — ANC4's date is unaffected by an HR visit inserted after
   * ANC3.
   *
   * @param existingHrCount how many ANC-HR rows the beneficiary already has, for sequencing.
   */
  fun generateHrVisit(
    context: ScheduleContext,
    triggeringVisit: VisitScheduleEntity,
    actualCompletionDate: LocalDate,
    existingHrCount: Int = 0,
    newUuid: () -> String = { UUID.randomUUID().toString() },
    createdAt: Instant = Instant.now(),
  ): VisitScheduleEntity? {
    // SR-NN-01 and the general rule: some families never generate HR follow-ups.
    if (!rules.supportsHrVisits(triggeringVisit.visitType)) return null

    val sequenceNo = existingHrCount + 1
    val scheduledDate = actualCompletionDate.plusDays(
      rules.hrOffsetDays(triggeringVisit.visitType).toLong(),
    )
    val window = rules.window(VisitCodeType.ANC_HR, sequenceNo, scheduledDate)

    return VisitScheduleEntity(
      localScheduleUuid = newUuid(),
      localBeneficiaryId = context.localBeneficiaryId,
      visitCode = "$ANC_HR_CODE_PREFIX$sequenceNo",
      visitType = VisitCodeType.ANC_HR,
      sequenceNo = sequenceNo,
      scheduledDate = scheduledDate,
      windowStartDate = window.start,
      windowEndDate = window.end,
      anchorType = AnchorType.ACTUAL_VISIT,
      anchorDate = actualCompletionDate,
      anchorVisitLocalUuid = triggeringVisit.localScheduleUuid,
      generatedByRuleVersion = rules.ruleVersion,
      escalationPolicy = rules.escalationPolicy(VisitCodeType.ANC_HR),
      createdAtEpochMillis = createdAt.toEpochMilli(),
    )
  }

  private companion object {
    /**
     * Display prefixes, not scheduling values — the Sakhi-facing label on the card. Kept here
     * rather than in [HardcodedRuleSource] because they are presentation, and moving them into the
     * rule source would mean GoRules owning UI copy.
     *
     * ANC-HR's exact label is not specified in the design boards; "ANC-HR1" is provisional and
     * should be confirmed when the visit tracker is built (CR-024).
     */
    const val ANC_CODE_PREFIX = "ANC"
    const val ANC_HR_CODE_PREFIX = "ANC-HR"

    /** The post-EDD visit is always a singleton — only one is ever generated per pregnancy. */
    const val POST_EDD_SEQUENCE_NO = 1
  }
}
