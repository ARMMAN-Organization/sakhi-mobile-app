package org.armman.sakhi.data.schedule

import java.time.LocalDate

/**
 * Visit family. Mirrors the server's `VisitCodeType` enum
 * (`arogyasakhi-service/apps/visit-form-service/prisma/schema.prisma`) name-for-name — the sync
 * payload sends these as strings, so a rename here silently breaks the upload. Keep in step.
 *
 * This is the *family* (`ANC`), distinct from the visit code the Sakhi sees (`ANC3`) which lives in
 * [VisitScheduleEntity.visitCode]. See CR-023 §4 for the agreed split.
 */
enum class VisitCodeType {
  ANC,
  ANC_HR,
  ANC_POST_EDD,
  DELIVERY,
  PP,
  NN,
  INC,
  INC_HR,
  CCV,
  CCV_HR,
}

/** What a visit's date is measured from. Mirrors the server's `AnchorType`. */
enum class AnchorType {
  REGISTRATION,
  LMP,
  EDD,
  DELIVERY_DATE,
  DOB,

  /**
   * The *actual* completion date of a triggering visit, not its scheduled date. Used only by
   * `*_HR` rows (SRS FR-S-3.4). The distinction is the single most misread rule in the SRS: a visit
   * completed five days late pushes its HR follow-up five days later too.
   */
  ACTUAL_VISIT,
  CCV_TRANSITION,
}

/**
 * Lifecycle of a scheduled visit. Mirrors the server's `VisitScheduleStatus`.
 *
 * NOTE — SRS FR-S-3.7 says open ANC visits are marked **LAPSED** when the delivery form is
 * submitted, but no `LAPSED` member exists in the server enum. Until ARMMAN rules on it (open
 * question Q3, `docs/plans/visit-flow-completion-crs.md`) a lapse is recorded as [CANCELLED] with
 * [VisitScheduleEntity.reasonCode] = [REASON_LAPSED_ON_DELIVERY]. If a distinct state is wanted,
 * both this enum and the server's need a migration — cheap now, expensive once field data exists.
 */
enum class VisitScheduleStatus {
  GENERATED,
  OPEN,
  MISSED,
  COMPLETED,
  SUPERSEDED,
  CANCELLED,
}

/** Reason code written alongside [VisitScheduleStatus.CANCELLED] when FR-S-3.7 lapsing applies. */
const val REASON_LAPSED_ON_DELIVERY = "LAPSED_ON_DELIVERY"

/**
 * The date range a visit may actually be performed in, inclusive at both ends.
 *
 * Deliberately holds resolved [LocalDate]s rather than offsets: the SRS mixes two window shapes —
 * symmetric (`scheduled ± 5`, most ANC/INC/PP rows) and fixed one-sided ranges (ANC1 is
 * `Day 0 → Day +5`; NN1 is `Day 0 → Day 14`). Storing offsets would force every caller to know
 * which shape applied. See [ScheduleRuleSource.window] for how each is produced.
 */
data class VisitWindow(val start: LocalDate, val end: LocalDate) {
  init {
    require(!end.isBefore(start)) { "Window end $end is before start $start" }
  }

  /** Inclusive at both ends — a visit performed on the closing day is in-window. */
  fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

  val lengthInDays: Int get() = (end.toEpochDay() - start.toEpochDay()).toInt() + 1
}

/**
 * When a missed visit escalates to the Supervisor. Set by the generator so the reconciler
 * (CR-027) can act without re-deriving the rule per visit type.
 *
 * SRS: 2 consecutive missed ANC or INC visits escalate (FR-S-3.5); a single missed HR, NN, PP or
 * post-EDD visit escalates immediately (FR-S-3.6, SR-ANC-01, and the NN/PP table notes).
 */
enum class EscalationPolicy {
  /** One missed visit escalates immediately. */
  IMMEDIATE,

  /** Two consecutive missed visits escalate. */
  AFTER_TWO_CONSECUTIVE,
}

/**
 * A high-risk condition found at a visit. Drives CCV's opening cadence — SAM and danger signs are
 * grouped because the SRS gives them the same 30-day response as "other HR".
 */
enum class HrFinding { SAM, DANGER_SIGN, OTHER }

/**
 * The outcome of one completed INC visit, as far as CCV scheduling is concerned.
 *
 * A deliberately narrow view of a visit: CCV needs only when it happened and whether a high-risk
 * condition was found. The full visit record lives in the visit form (CR-026); coupling the
 * scheduling engine to it would make this generator untestable without the form.
 */
data class IncVisitOutcome(
  val scheduleLocalUuid: String,
  val completedOn: LocalDate,
  /** Null when the visit found no high-risk condition. */
  val hrFinding: HrFinding? = null,
)

/**
 * The child's risk state at the INC-to-CCV transition, evaluated **once** (SRS CCV risk-state
 * table). It selects the opening CCV cadence and is not re-evaluated as the CCV phase progresses.
 */
enum class CcvRiskState {
  /** No HR condition anywhere in the full 0–12m period. Two-monthly cadence. */
  NEVER_AT_HR,

  /** SAM or a danger sign at the most recent INC visit. Monthly HR visit. */
  CURRENTLY_HR_SAM_OR_DANGER,

  /** Another HR condition at the most recent INC visit, even if previously triggered. Monthly. */
  CURRENTLY_HR_OTHER,

  /**
   * An HR condition occurred during the infant phase but not at the most recent visit.
   *
   * **Not a state the SRS defines** — its table has only the three above, and "never at HR"
   * explicitly requires a clean full scan, so a child with a resolved past condition matches no
   * row. Treated as the standard two-monthly cadence but recorded distinctly so the assumption is
   * visible rather than silently folded into [NEVER_AT_HR]. Open question Q5 for ARMMAN.
   */
  PREVIOUSLY_AT_HR,
}

/**
 * Everything the generator needs about a beneficiary to produce a schedule. Assembled by the
 * caller at submit time; the generator never reaches into repositories itself, which is what keeps
 * it a pure function and unit-testable without Room or Hilt.
 *
 * Nullability follows the flows: [lmp]/[edd] are mother-only, [dob] child-only, [deliveryDate] and
 * [deliveryFormFilledOn] only exist once a delivery form is submitted.
 */
data class ScheduleContext(
  val localBeneficiaryId: String,
  val registrationDate: LocalDate,
  val lmp: LocalDate? = null,
  val edd: LocalDate? = null,
  val dob: LocalDate? = null,
  val deliveryDate: LocalDate? = null,
  /**
   * The date the delivery *form* was filled, which is not the delivery date and drives the whole
   * NN scenario A/B/C split (SRS FR-S-2.2A). Distinct field precisely because they diverge.
   */
  val deliveryFormFilledOn: LocalDate? = null,
)
