package org.armman.sakhi.data.schedule

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the five postpartum visits, on the device, when the delivery form is submitted
 * (SRS §3A.2.3 "PP Visit Schedule").
 *
 * All five are anchored to the **delivery date**, not to the date the form was filled, and the
 * schedule does not shift based on actual completion dates.
 *
 * ### Two window shapes in one family
 * PP1 and PP2 use fixed ranges measured from the delivery date (`Day 0→14`, `Day 15→28`); PP3–PP5
 * use symmetric ±5 windows around their own scheduled dates. Both shapes come from
 * [ScheduleRuleSource]; this class only asks which applies via
 * [ScheduleRuleSource.usesFixedRangeWindow].
 *
 * ### The dates are provisional
 * The SRS's PP table gives Anchor, Scheduled and Window-close columns that disagree. See
 * `HardcodedRuleSource.Pp` for the reconciliation and open question Q1. Because every number lives
 * in the rule source, an ARMMAN correction is a one-line change here-adjacent, not a rewrite.
 */
@Singleton
class PpScheduleGenerator @Inject constructor(
  private val rules: ScheduleRuleSource,
) {

  /**
   * The full PP series. Requires [ScheduleContext.deliveryDate].
   *
   * Generated at delivery form submission — [generateSeries] is never called at enrolment, and a
   * context with no delivery date is a programming error rather than an empty result.
   */
  fun generateSeries(
    context: ScheduleContext,
    newUuid: () -> String = { UUID.randomUUID().toString() },
    createdAt: Instant = Instant.now(),
  ): List<VisitScheduleEntity> {
    val deliveryDate = requireNotNull(context.deliveryDate) {
      "PP schedule requires a delivery date"
    }

    val count = rules.visitCount(VisitCodeType.PP, context)
    val createdAtMillis = createdAt.toEpochMilli()

    return (1..count).map { sequenceNo ->
      val scheduledDate = deliveryDate.plusDays(
        rules.scheduledOffsetDays(VisitCodeType.PP, sequenceNo).toLong(),
      )
      val window = windowFor(sequenceNo, deliveryDate, scheduledDate)

      VisitScheduleEntity(
        localScheduleUuid = newUuid(),
        localBeneficiaryId = context.localBeneficiaryId,
        visitCode = "$PP_CODE_PREFIX$sequenceNo",
        visitType = VisitCodeType.PP,
        sequenceNo = sequenceNo,
        scheduledDate = scheduledDate,
        windowStartDate = window.start,
        windowEndDate = window.end,
        anchorType = AnchorType.DELIVERY_DATE,
        anchorDate = deliveryDate,
        generatedByRuleVersion = rules.ruleVersion(VisitCodeType.PP),
        escalationPolicy = rules.escalationPolicy(VisitCodeType.PP),
        createdAtEpochMillis = createdAtMillis,
      )
    }
  }

  /**
   * True when completing this visit should raise the mother-closure prompt — the last PP visit
   * ends the program cycle ("PP5 completion triggers the mother closure prompt").
   *
   * Derived from the series length rather than a literal 5, so a change to the PP schedule cannot
   * leave the closure trigger pointing at the wrong visit.
   */
  fun isClosurePromptTrigger(visit: VisitScheduleEntity, context: ScheduleContext): Boolean =
    visit.visitType == VisitCodeType.PP &&
      visit.sequenceNo == rules.visitCount(VisitCodeType.PP, context)

  /**
   * PP1/PP2 windows are ranges from the delivery date; PP3–PP5 are spans around their own dates.
   *
   * The fixed-range lookup returns null only when a window has already closed, which cannot happen
   * for PP — unlike NN, no PP visit is conditional on when the form was filed — so a null here is a
   * rule-source inconsistency worth failing on rather than silently skipping a visit.
   */
  private fun windowFor(
    sequenceNo: Int,
    deliveryDate: LocalDate,
    scheduledDate: LocalDate,
  ): VisitWindow = if (rules.usesFixedRangeWindow(VisitCodeType.PP, sequenceNo)) {
    requireNotNull(rules.fixedRangeWindow(VisitCodeType.PP, sequenceNo, deliveryDate)) {
      "PP$sequenceNo has no fixed-range window defined"
    }
  } else {
    rules.window(VisitCodeType.PP, sequenceNo, scheduledDate)
  }

  private companion object {
    /** Display prefix — presentation, not a scheduling value. */
    const val PP_CODE_PREFIX = "PP"
  }
}
