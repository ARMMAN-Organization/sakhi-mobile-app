package org.armman.sakhi.data.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the neonatal visits (SRS FR-S-2.2A, SR-NN-01).
 *
 * The trickiest generator in the engine, because NN is the only family whose *shape* depends on
 * when the delivery form was filled rather than on when the event happened.
 *
 * ### The three scenarios
 * | Scenario | Form filled | NN1 | NN2 |
 * |---|---|---|---|
 * | A | Day 0–14 | Generated, window Day 0–14 | Generated, window Day 15–28 |
 * | B | Day 15–27 | **Skipped** | Generated, window = fill date → Day 28 |
 * | C | Day 28 | **Skipped** | Generated, same session |
 * | — | After Day 28 | Nothing | Nothing |
 *
 * ### "Skipped" is not "missed"
 * In scenarios B and C, NN1's window has already closed by the time the form arrives. The SRS is
 * explicit that it is *"Skipped — window closed. Not generated. Not marked missed."* So no row is
 * written at all — writing one as MISSED would put a failure on the Sakhi's record for a visit that
 * was never possible, and would trip the immediate-escalation rule for a missed NN visit.
 *
 * ### NN2 keeps its number
 * In scenario B the only visit generated is still called **NN2**, not renumbered to NN1. The
 * numbering describes the neonatal stage, not the position in the list.
 *
 * ### No high-risk visits here
 * SR-NN-01: a critical condition found during NN1 or NN2 goes to the referral flow. The engine
 * never generates an HR row for the neonatal phase — enforced by
 * [ScheduleRuleSource.supportsHrVisits] returning false for NN.
 */
@Singleton
class NnScheduleGenerator @Inject constructor(
  private val rules: ScheduleRuleSource,
) {

  /**
   * The NN series for this beneficiary. Returns an empty list when the delivery form arrived after
   * the neonatal period closed — a real outcome, not an error.
   *
   * Requires [ScheduleContext.deliveryDate] and [ScheduleContext.deliveryFormFilledOn]; the whole
   * scenario split is derived from the gap between them.
   */
  fun generateSeries(
    context: ScheduleContext,
    newUuid: () -> String = { UUID.randomUUID().toString() },
    createdAt: Instant = Instant.now(),
  ): List<VisitScheduleEntity> {
    val deliveryDate = requireNotNull(context.deliveryDate) {
      "NN schedule requires a delivery date"
    }
    val filledOn = requireNotNull(context.deliveryFormFilledOn) {
      "NN schedule requires the date the delivery form was filled"
    }

    // The rule source owns the scenario boundaries; a count of 2 means NN1 is still open, 1 means
    // only NN2 survives, 0 means the neonatal period has closed entirely.
    val count = rules.visitCount(VisitCodeType.NN, context)
    if (count == 0) return emptyList()

    val includesNn1 = count == VISITS_WHEN_NN1_STILL_OPEN
    val createdAtMillis = createdAt.toEpochMilli()

    return buildList {
      if (includesNn1) {
        // A visit cannot be performed before its delivery form exists, so NN1's nominal Day 0 start
        // is clamped forward to the fill date. In scenario A the two are usually the same day.
        buildVisit(
          context = context,
          sequenceNo = NN1,
          scheduledDate = filledOn,
          deliveryDate = deliveryDate,
          notBefore = filledOn,
          newUuid = newUuid,
          createdAtMillis = createdAtMillis,
        )?.let(::add)
      }

      // Scenario A schedules NN2 at the start of its own window (Day 15); B and C schedule it for
      // the fill date, because the Sakhi is already with the beneficiary.
      val nn2ScheduledDate = if (includesNn1) {
        deliveryDate.plusDays(nn2WindowStartOffset(deliveryDate))
      } else {
        filledOn
      }

      buildVisit(
        context = context,
        sequenceNo = NN2,
        scheduledDate = nn2ScheduledDate,
        deliveryDate = deliveryDate,
        notBefore = if (includesNn1) null else filledOn,
        newUuid = newUuid,
        createdAtMillis = createdAtMillis,
      )?.let(::add)
    }
  }

  /**
   * Builds one NN row, or null if its window has already closed.
   *
   * Null is reachable and expected — it is how a skipped NN1 disappears without leaving a MISSED
   * row behind. Callers must not treat it as an error.
   */
  private fun buildVisit(
    context: ScheduleContext,
    sequenceNo: Int,
    scheduledDate: LocalDate,
    deliveryDate: LocalDate,
    notBefore: LocalDate?,
    newUuid: () -> String,
    createdAtMillis: Long,
  ): VisitScheduleEntity? {
    val window = rules.fixedRangeWindow(VisitCodeType.NN, sequenceNo, deliveryDate, notBefore)
      ?: return null

    return VisitScheduleEntity(
      localScheduleUuid = newUuid(),
      localBeneficiaryId = context.localBeneficiaryId,
      visitCode = "$NN_CODE_PREFIX$sequenceNo",
      visitType = VisitCodeType.NN,
      sequenceNo = sequenceNo,
      // Never schedule a visit outside its own window — matters in scenario C, where the fill date
      // sits on the closing day. Clamped explicitly rather than with coerceIn, because LocalDate
      // implements Comparable<ChronoLocalDate> and inference can widen the result away from
      // LocalDate.
      scheduledDate = clampToWindow(scheduledDate, window),
      windowStartDate = window.start,
      windowEndDate = window.end,
      anchorType = AnchorType.DELIVERY_DATE,
      anchorDate = deliveryDate,
      generatedByRuleVersion = rules.ruleVersion(VisitCodeType.NN),
      escalationPolicy = rules.escalationPolicy(VisitCodeType.NN),
      createdAtEpochMillis = createdAtMillis,
    )
  }

  private fun clampToWindow(date: LocalDate, window: VisitWindow): LocalDate = when {
    date.isBefore(window.start) -> window.start
    date.isAfter(window.end) -> window.end
    else -> date
  }

  /** NN2's nominal opening day, read back off the rule source rather than assumed to be 15. */
  private fun nn2WindowStartOffset(deliveryDate: LocalDate): Long {
    val window = requireNotNull(
      rules.fixedRangeWindow(VisitCodeType.NN, NN2, deliveryDate),
    ) { "NN2 has no fixed-range window defined" }
    return ChronoUnit.DAYS.between(deliveryDate, window.start)
  }

  private companion object {
    const val NN_CODE_PREFIX = "NN"

    /** Sequence numbers are identity here, not counts — NN2 stays NN2 even when it is the only row. */
    const val NN1 = 1
    const val NN2 = 2

    /** A rule-source count of 2 means the form arrived while NN1's window was still open. */
    const val VISITS_WHEN_NN1_STILL_OPEN = 2
  }
}
