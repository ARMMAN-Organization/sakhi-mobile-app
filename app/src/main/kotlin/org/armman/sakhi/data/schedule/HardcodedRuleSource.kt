package org.armman.sakhi.data.schedule

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every scheduling constant in the app, in one file. Milestone 2 implementation of
 * [ScheduleRuleSource]; replaced by `GoRulesRuleSource` in M3 (CR-032) with no change to the
 * generator.
 *
 * **Nothing outside this file may contain a scheduling number.** That is what keeps the M3
 * migration a one-line binding swap. Guarded by `HardcodedRuleSourceTest` and by code review.
 *
 * Source of truth: `docs/project-docs/Arogya_Sakhi_SRS_v3.0.md` §3A.2.3 (lines 247–329).
 * Values still awaiting ARMMAN confirmation are marked `PENDING ARMMAN` with the question number
 * from `docs/plans/visit-flow-completion-crs.md`.
 */
@Singleton
class HardcodedRuleSource @Inject constructor() : ScheduleRuleSource {

  /**
   * The seeded `rule_versions` row representing "rules implemented in Kotlin, M2".
   *
   * **Must equal the UUID the backend seeds** (CR-023 §3) or every bulk upload fails with
   * `UNKNOWN_RULE_VERSION`. Placeholder until backend supplies it — `HardcodedRuleSourceTest`
   * asserts on it so the swap is not forgotten.
   */
  override val ruleVersion: String = SEEDED_RULE_VERSION_ID

  override fun intervalDays(visitType: VisitCodeType): Int = when (visitType) {
    VisitCodeType.ANC -> Anc.INTERVAL_DAYS
    VisitCodeType.INC -> Inc.INTERVAL_DAYS
    VisitCodeType.CCV -> Ccv.STANDARD_INTERVAL_DAYS
    VisitCodeType.ANC_HR, VisitCodeType.INC_HR, VisitCodeType.CCV_HR -> Hr.OFFSET_DAYS
    // PP and NN are explicit date tables, not intervals — see Pp.SCHEDULED_OFFSET_DAYS / Nn.
    VisitCodeType.PP, VisitCodeType.NN, VisitCodeType.DELIVERY, VisitCodeType.ANC_POST_EDD ->
      error("$visitType has no fixed interval; it uses an explicit offset table")
  }

  override fun visitCount(visitType: VisitCodeType, context: ScheduleContext): Int =
    when (visitType) {
      VisitCodeType.ANC -> ancVisitCount(context)
      VisitCodeType.ANC_POST_EDD -> Anc.POST_EDD_VISIT_COUNT
      VisitCodeType.PP -> Pp.SCHEDULED_OFFSET_DAYS.size
      VisitCodeType.NN -> nnVisitCount(context)
      VisitCodeType.INC -> incAdditionalVisitCount(context)
      VisitCodeType.CCV -> Ccv.DEFAULT_VISIT_COUNT
      // HR visits are generated one at a time in response to a detection, never as a series.
      VisitCodeType.ANC_HR, VisitCodeType.INC_HR, VisitCodeType.CCV_HR -> 1
      VisitCodeType.DELIVERY -> 1
    }

  override fun window(
    visitType: VisitCodeType,
    sequenceNo: Int,
    scheduledDate: LocalDate,
  ): VisitWindow = when (visitType) {
    // FR-S-3.2: ANC1 is one-sided Day 0 → Day +5. FR-S-3.3: ANC2+ are symmetric ±5.
    VisitCodeType.ANC ->
      if (sequenceNo == 1) forward(scheduledDate, Anc.FIRST_VISIT_WINDOW_FORWARD_DAYS)
      else symmetric(scheduledDate, Anc.WINDOW_DAYS)

    // SR-ANC-01: one-sided EDD+8 → EDD+13.
    VisitCodeType.ANC_POST_EDD -> forward(scheduledDate, Anc.POST_EDD_WINDOW_FORWARD_DAYS)

    // PP1/PP2 are fixed ranges from the delivery date; PP3–PP5 are symmetric ±5.
    VisitCodeType.PP ->
      if (usesFixedRangeWindow(VisitCodeType.PP, sequenceNo)) {
        error("PP$sequenceNo is a fixed-range window — call fixedRangeWindow()")
      } else {
        symmetric(scheduledDate, Pp.WINDOW_DAYS)
      }

    VisitCodeType.NN -> error("NN windows are fixed ranges — call fixedRangeWindow()")

    VisitCodeType.INC -> symmetric(scheduledDate, Inc.WINDOW_DAYS)
    VisitCodeType.CCV -> symmetric(scheduledDate, Ccv.WINDOW_DAYS)

    VisitCodeType.ANC_HR, VisitCodeType.INC_HR, VisitCodeType.CCV_HR ->
      symmetric(scheduledDate, Hr.WINDOW_DAYS)

    // DELIVERY is an event the Sakhi records, not a visit the engine schedules a window for.
    // Deliberately unsupported rather than borrowing another family's constant.
    VisitCodeType.DELIVERY -> error("DELIVERY is not a scheduled visit and has no window")
  }

  override fun usesFixedRangeWindow(visitType: VisitCodeType, sequenceNo: Int): Boolean =
    when (visitType) {
      VisitCodeType.PP -> sequenceNo <= Pp.FIXED_RANGE_VISIT_COUNT
      VisitCodeType.NN -> true
      else -> false
    }

  override fun fixedRangeWindow(
    visitType: VisitCodeType,
    sequenceNo: Int,
    anchorDate: LocalDate,
    notBefore: LocalDate?,
  ): VisitWindow? {
    val offsets = when (visitType) {
      VisitCodeType.PP -> Pp.FIXED_WINDOWS[sequenceNo]
      VisitCodeType.NN -> Nn.FIXED_WINDOWS[sequenceNo]
      else -> error("$visitType has no fixed-range window; check usesFixedRangeWindow() first")
    } ?: error("$visitType$sequenceNo has no fixed-range window defined")

    val (startOffset, endOffset) = offsets
    val end = anchorDate.plusDays(endOffset.toLong())
    val nominalStart = anchorDate.plusDays(startOffset.toLong())
    val start = if (notBefore != null && notBefore.isAfter(nominalStart)) notBefore else nominalStart

    // Window already closed — NN scenario B's NN1, or anything asked for after Day 28. The SRS
    // treats this as "skipped, not missed", so it is a null result rather than a thrown error.
    return if (start.isAfter(end)) null else VisitWindow(start, end)
  }

  override fun scheduledOffsetDays(visitType: VisitCodeType, sequenceNo: Int): Int =
    when (visitType) {
      VisitCodeType.PP -> Pp.SCHEDULED_OFFSET_DAYS.getOrElse(sequenceNo - 1) {
        error("PP$sequenceNo is outside the ${Pp.SCHEDULED_OFFSET_DAYS.size}-visit PP schedule")
      }
      else -> error("$visitType uses a repeated interval, not an offset table — call intervalDays()")
    }

  override fun isEarlyIncRegistration(dob: LocalDate, registrationDate: LocalDate): Boolean =
    ChronoUnit.DAYS.between(dob, registrationDate) in 0..Inc.EARLY_REGISTRATION_MAX_DAY

  override fun incFirstVisitOffsetDays(): Int = Inc.FIRST_VISIT_OFFSET_DAYS

  override fun incPhaseEndDays(): Int = Inc.YEAR_DAYS.toInt()

  override fun postEddGraceDays(): Int = Anc.POST_EDD_TRIGGER_GRACE_DAYS

  override fun postEddOffsetDays(): Int = Anc.POST_EDD_OFFSET_DAYS

  override fun eddOffsetDays(): Int = Anc.EDD_OFFSET_DAYS

  override fun hrPerDetection(): Boolean = Hr.PER_DETECTION

  /**
   * HR follow-up offset. CCV's high-risk cadence is monthly (SRS CCV risk-state table: "HR visit in
   * 30 days"), where ANC and INC use 15 days from the triggering visit's actual completion
   * (FR-S-3.4). Distinct constants, not one shared value.
   */
  override fun hrOffsetDays(visitType: VisitCodeType): Int = when (visitType) {
    VisitCodeType.CCV, VisitCodeType.CCV_HR -> Ccv.HR_OFFSET_DAYS
    else -> Hr.OFFSET_DAYS
  }

  override fun cutoffDays(visitType: VisitCodeType): Int? = when (visitType) {
    // "Any INC visit with a scheduled date beyond DOB + 370 is dropped" — 365 + a 5-day buffer.
    VisitCodeType.INC -> Inc.CUTOFF_DAYS_FROM_DOB
    else -> null
  }

  override fun escalationPolicy(visitType: VisitCodeType): EscalationPolicy = when (visitType) {
    // FR-S-3.5 names ANC and INC explicitly: two consecutive misses escalate.
    // CCV is NOT named anywhere in the SRS's escalation rules. Treated as a routine visit family
    // like ANC/INC rather than as an HR visit — recorded as an assumption, pinned by
    // HardcodedRuleSourceTest, and raised with ARMMAN as open question Q4.
    VisitCodeType.ANC, VisitCodeType.INC, VisitCodeType.CCV ->
      EscalationPolicy.AFTER_TWO_CONSECUTIVE
    // FR-S-3.6 + SR-ANC-01 + the NN/PP table notes: a single miss escalates immediately.
    else -> EscalationPolicy.IMMEDIATE
  }

  // SR-NN-01: no HR visits during the neonatal phase — a critical condition at NN1/NN2 routes to
  // the referral flow instead.
  override fun supportsHrVisits(visitType: VisitCodeType): Boolean = when (visitType) {
    VisitCodeType.ANC, VisitCodeType.INC, VisitCodeType.CCV -> true
    else -> false
  }

  // ---------------------------------------------------------------------------------------------
  // Shape helpers for the two window forms the SRS uses.
  // ---------------------------------------------------------------------------------------------

  /** `scheduled ± days`. */
  private fun symmetric(scheduled: LocalDate, days: Int) =
    VisitWindow(scheduled.minusDays(days.toLong()), scheduled.plusDays(days.toLong()))

  /** `scheduled → scheduled + days`, no backward tolerance. */
  private fun forward(scheduled: LocalDate, days: Int) =
    VisitWindow(scheduled, scheduled.plusDays(days.toLong()))

  // ---------------------------------------------------------------------------------------------
  // Count formulas
  // ---------------------------------------------------------------------------------------------

  /**
   * FR-S-3.1 — `((EDD − registration date) / 30) + 1`, integer division, uncapped. A woman
   * registered on her LMP date gets 10; one registered on or after her EDD still gets ANC1.
   */
  private fun ancVisitCount(context: ScheduleContext): Int {
    val edd = requireNotNull(context.edd) { "ANC schedule requires an EDD" }
    val daysToEdd = ChronoUnit.DAYS.between(context.registrationDate, edd)
    return max(1, (daysToEdd / Anc.INTERVAL_DAYS).toInt() + 1)
  }

  /**
   * FR-S-2.2A NN scenarios, keyed on when the delivery *form* was filled relative to the delivery
   * date: A (Day 0–14) generates NN1 and NN2; B (Day 15–27) and C (Day 28) skip NN1 — it is not
   * generated and not marked missed — leaving NN2 only; past Day 28 nothing is generated.
   */
  private fun nnVisitCount(context: ScheduleContext): Int {
    val deliveryDate = requireNotNull(context.deliveryDate) { "NN schedule requires a delivery date" }
    val filledOn = requireNotNull(context.deliveryFormFilledOn) {
      "NN schedule requires the delivery form fill date"
    }
    return when (ChronoUnit.DAYS.between(deliveryDate, filledOn)) {
      in 0..Nn.SCENARIO_A_MAX_DAY -> 2
      in Nn.SCENARIO_A_MAX_DAY + 1..Nn.SCENARIO_C_DAY -> 1
      else -> 0
    }
  }

  /**
   * INC count — the number of visits **after** INC1, per the SRS's own wording.
   *
   * Early registration (DOB Day 0–58): `Round((365 − 58) / 30)` = 10, giving INC1…INC11.
   * Late registration: `Round((365 − (registration − DOB)) / 30)`, with INC1 on the registration
   * date itself. Never negative — a child registered at Day 360 gets INC1 alone.
   */
  private fun incAdditionalVisitCount(context: ScheduleContext): Int {
    val dob = requireNotNull(context.dob) { "INC schedule requires a DOB" }
    val ageAtRegistration = ChronoUnit.DAYS.between(dob, context.registrationDate)
    val remainingDays = if (isEarlyIncRegistration(dob, context.registrationDate)) {
      Inc.YEAR_DAYS - Inc.FIRST_VISIT_OFFSET_DAYS
    } else {
      Inc.YEAR_DAYS - ageAtRegistration
    }
    return max(0, (remainingDays.toDouble() / Inc.INTERVAL_DAYS).roundToInt())
  }

  // ---------------------------------------------------------------------------------------------
  // The constants. Everything above reads from here; nothing else in the app should hold these.
  // ---------------------------------------------------------------------------------------------

  /** ANC — SRS FR-S-3.1 … FR-S-3.3, SR-ANC-01. */
  private object Anc {
    const val INTERVAL_DAYS = 30

    /** FR-S-2.1: EDD = LMP + 280. Mirrors the form's own `EDD_FROM_LMP` evaluator. */
    const val EDD_OFFSET_DAYS = 280
    const val WINDOW_DAYS = 5
    const val FIRST_VISIT_WINDOW_FORWARD_DAYS = 5
    const val POST_EDD_VISIT_COUNT = 1

    /** SR-ANC-01: generated at EDD+8 when no delivery form exists by EDD+7. */
    const val POST_EDD_OFFSET_DAYS = 8
    const val POST_EDD_TRIGGER_GRACE_DAYS = 7
    const val POST_EDD_WINDOW_FORWARD_DAYS = 5
  }

  /**
   * PP — anchored to the delivery date, generated at delivery form submission.
   *
   * ⚠ **PENDING ARMMAN (Q1).** The SRS table's Anchor / Scheduled / Window-close columns appear to
   * contradict each other. They reconcile if "Anchor" is the scheduled date and "Scheduled" is the
   * window start: PP3 58 ± 5 = 53–63 ✓, PP4 88 ± 5 = 83–93 ✓, PP5 118 ± 5 = 113–123 ✓. Under that
   * reading the only error is the table's PP5 anchor of "105", which its own formula contradicts —
   * the text says "PP4 scheduled (88 + 30)", and 88 + 30 = 118. Implemented as 118.
   */
  private object Pp {
    val SCHEDULED_OFFSET_DAYS = intArrayOf(0, 15, 58, 88, 118)
    const val WINDOW_DAYS = 5

    /** PP1 and PP2 use fixed ranges rather than ±5. */
    const val FIXED_RANGE_VISIT_COUNT = 2
    val FIXED_WINDOWS: Map<Int, Pair<Int, Int>> = mapOf(1 to (0 to 14), 2 to (15 to 28))
  }

  /** NN — SRS FR-S-2.2A, SR-NN-01. Windows are fixed ranges, never ±N. */
  private object Nn {
    const val SCENARIO_A_MAX_DAY = 14
    const val SCENARIO_C_DAY = 28
    val FIXED_WINDOWS: Map<Int, Pair<Int, Int>> = mapOf(1 to (0 to 14), 2 to (15 to 28))
  }

  /** INC — two-formula approach, 0–12 months. */
  private object Inc {
    const val INTERVAL_DAYS = 30
    const val WINDOW_DAYS = 5

    /** End of the neonatal period; INC1's anchor for an early registration. */
    const val FIRST_VISIT_OFFSET_DAYS = 58
    const val EARLY_REGISTRATION_MAX_DAY = 58L
    const val YEAR_DAYS = 365L

    /** DOB + 365 + a 5-day buffer. Visits beyond this are dropped, not marked missed. */
    const val CUTOFF_DAYS_FROM_DOB = 370
  }

  /** CCV — 13–24 months, generated at the INC-to-CCV transition, not at registration. */
  private object Ccv {
    /** "Never at HR" cadence: one visit every two months. */
    const val STANDARD_INTERVAL_DAYS = 60
    const val WINDOW_DAYS = 5

    /**
     * "Never at HR" baseline: six two-monthly visits across 13–24 months. The risk-state-dependent
     * cadence is resolved by the CCV generator in CR-022d, which reads [HR_OFFSET_DAYS].
     */
    const val DEFAULT_VISIT_COUNT = 6

    /**
     * CCV high-risk cadence — "HR visit in 30 days (every detection)" per the SRS risk-state table.
     * Deliberately its own constant: ANC and INC use 15 days ([Hr.OFFSET_DAYS]), and sharing one
     * value would silently halve or double the other family's interval.
     */
    const val HR_OFFSET_DAYS = 30
  }

  /**
   * HR follow-ups — FR-S-3.4. Anchored to the **actual** completion date of the triggering visit,
   * never its scheduled date.
   *
   * ⚠ **PENDING ARMMAN (Q2).** Which conditions trigger an HR visit, and whether one is generated
   * per detection or once per pregnancy, is unconfirmed. [PER_DETECTION] is the interim reading;
   * the trigger itself is supplied by the caller, so only this flag changes if the answer differs.
   */
  private object Hr {
    const val OFFSET_DAYS = 15
    const val WINDOW_DAYS = 2
    const val PER_DETECTION = true
  }

  companion object {
    /**
     * The `rule_versions.rule_version_id` the backend seeds for the `v1-hardcoded` scheduling rule
     * version (their PR #102, confirmed 2026-08-05).
     *
     * **This must match the server exactly** — `POST /visit-schedules/bulk` rejects an unknown
     * version with `UNKNOWN_RULE_VERSION`, so a mismatch fails every upload. It is hardcoded in
     * their seed script rather than generated per run, so it is identical in dev, SIT and UAT.
     *
     * The only member exposed outside this class, and deliberately not a scheduling value: the
     * post-EDD offsets and the HR-per-detection flag are reached through [ScheduleRuleSource]
     * instead, so no caller can bypass the seam to read a constant directly.
     */
    const val SEEDED_RULE_VERSION_ID = "22222222-2222-4222-8222-222222222222"
  }
}
