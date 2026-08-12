package org.armman.sakhi.data.schedule

import java.time.LocalDate

/**
 * Supplies every value the schedule generator needs. **The generator must never contain a
 * scheduling number itself** — it asks this interface for intervals, counts, windows and cut-offs,
 * and applies them.
 *
 * ### Why this exists
 * SRS §3A.2.3 requires two things that pull against each other on Android: schedules must be
 * generated *offline on the device*, and the rules must live in *GoRules as config, not code*.
 * There is no GoRules/ZEN runtime for Kotlin in this project. The agreed resolution (decision 5,
 * `docs/plans/visit-flow-completion-crs.md`) splits it across milestones:
 *
 *  - **M2 (CR-022):** [HardcodedRuleSource] — values as Kotlin constants, in one file.
 *  - **M3 (CR-032):** `GoRulesRuleSource` — same interface, values from a published rule package.
 *    One `@Binds` flip in `di/ScheduleModule`; [VisitScheduleGenerator] does not change.
 *
 * This is the same static→remote seam the app already uses for repositories, applied to rules.
 *
 * ### The one rule that makes it work
 * If a scheduling constant leaks into the generator, M3 stops being a binding swap and becomes a
 * rewrite. `HardcodedRuleSourceTest` and code review both guard this. Treat a bare number in
 * `data/schedule/` outside [HardcodedRuleSource] as a defect.
 */
interface ScheduleRuleSource {

  /**
   * Identifies the rule set that produced a schedule; stamped on every generated row as
   * [VisitScheduleEntity.generatedByRuleVersion].
   *
   * Takes [visitType] because M3's GoRules packages are versioned **per rule set** — ANC, PP, NN,
   * INC, CCV and HR each publish independently, so "the current version" is only meaningful once
   * you say which family you mean. [HardcodedRuleSource] returns the same constant for every
   * [VisitCodeType] because M2 is one Kotlin file; `GoRulesRuleSource` returns the version of
   * whichever rule set actually produced this value.
   *
   * Must match a published `rule_versions.rule_version_id` on the server — the bulk upload rejects
   * an unknown version with `UNKNOWN_RULE_VERSION` (CR-023 §5.1). Load-bearing for M3: it is how
   * v1-generated schedules are told apart from v2 and left alone.
   */
  fun ruleVersion(visitType: VisitCodeType): String

  /** Days between consecutive visits of a family — 30 for ANC, INC and the PP chain. */
  fun intervalDays(visitType: VisitCodeType): Int

  /**
   * How many visits of this family to generate, given the beneficiary's dates.
   *
   * Returns the count of visits *after* the first for the INC families, matching the SRS formulas
   * ("this formula gives the number of additional visits after INC1"); for every other family it is
   * the total. Implementations must never return a negative number — a woman registered after her
   * EDD still gets ANC1.
   */
  fun visitCount(visitType: VisitCodeType, context: ScheduleContext): Int

  /**
   * The window for a visit whose range is measured from its own scheduled date — symmetric
   * (`scheduled ± 5`, most ANC/INC/PP/CCV rows) or one-sided forward (ANC1 is `Day 0 → Day +5`,
   * the post-EDD visit is `EDD+8 → EDD+13`). Only the rule source knows which shape applies, which
   * is why callers pass the resolved date and ask rather than computing an offset themselves.
   *
   * [sequenceNo] matters — ANC1 is one-sided while ANC2+ are symmetric.
   *
   * **Check [usesFixedRangeWindow] first.** PP1/PP2 and every NN visit are measured from the
   * delivery date instead, and go through [fixedRangeWindow]; calling this for one of those throws.
   */
  fun window(visitType: VisitCodeType, sequenceNo: Int, scheduledDate: LocalDate): VisitWindow

  /**
   * True when this visit's window is a fixed range from its anchor rather than a span around its
   * scheduled date — PP1 (`Day 0→14`), PP2 (`Day 15→28`) and all NN visits. Callers branch on this
   * to choose between [window] and [fixedRangeWindow].
   */
  fun usesFixedRangeWindow(visitType: VisitCodeType, sequenceNo: Int): Boolean

  /**
   * The window for a fixed-range visit, measured from [anchorDate] (the delivery date for PP and
   * NN).
   *
   * [notBefore] clamps the window's opening — NN scenario B fills the delivery form partway through
   * NN2's range, so the visit opens on the fill date and still closes at Day 28 (SRS FR-S-2.2A).
   * Pass null when no clamp applies.
   *
   * Returns **null when the window has already closed** — scenario B's NN1, and anything requested
   * after Day 28. That case is a real one the SRS calls out ("skipped — not generated, not marked
   * missed"), so it is an ordinary result rather than an error, and callers must handle it.
   */
  fun fixedRangeWindow(
    visitType: VisitCodeType,
    sequenceNo: Int,
    anchorDate: LocalDate,
    notBefore: LocalDate? = null,
  ): VisitWindow?

  /**
   * Days from a family's anchor to visit [sequenceNo], for families whose dates come from an
   * explicit table rather than a repeated interval. PP only: `0, 15, 58, 88, 118` from the delivery
   * date.
   */
  fun scheduledOffsetDays(visitType: VisitCodeType, sequenceNo: Int): Int

  /**
   * Whether a child registered on [registrationDate] falls in the INC "early" band (DOB Day 0–58),
   * which selects between the two INC count formulas and moves INC1's anchor from the registration
   * date to DOB + [incFirstVisitOffsetDays].
   *
   * False for a [registrationDate] before [dob] — that is bad data, not an early registration.
   */
  fun isEarlyIncRegistration(dob: LocalDate, registrationDate: LocalDate): Boolean

  /** INC1's offset from DOB for an early registration — the end of the neonatal period. */
  fun incFirstVisitOffsetDays(): Int

  /**
   * Days from DOB to the nominal end of the infant phase (365 — twelve months), which is where the
   * CCV journey begins when no INC visit was ever generated.
   *
   * Distinct from `cutoffDays(INC)` (370): that is 365 plus a five-day buffer and governs which
   * visits are dropped, whereas this is the phase boundary itself.
   */
  fun incPhaseEndDays(): Int

  /**
   * SR-ANC-01 — days after the EDD within which a delivery form still counts as filed on time.
   * A form filed on `EDD + this` inclusive suppresses the post-EDD visit; later does not.
   */
  fun postEddGraceDays(): Int

  /** SR-ANC-01 — the post-EDD visit's offset from the EDD. One day past [postEddGraceDays]. */
  fun postEddOffsetDays(): Int

  /**
   * Days from LMP to EDD (280), per FR-S-2.1.
   *
   * Exposed because the mother-registration form computes EDD as a display field rather than
   * storing it, so the scheduling trigger has to derive it from the LMP. Same rule as the form's
   * own `EDD_FROM_LMP` evaluator — kept here so the two cannot drift apart silently.
   */
  fun eddOffsetDays(): Int

  /**
   * Whether a high-risk follow-up is generated on every detection, or only once per journey.
   *
   * Unconfirmed by ARMMAN (open question Q2). Exposed here rather than read from a constant so the
   * answer changes in one place, and so M3's rule packages can carry it as data.
   */
  fun hrPerDetection(): Boolean

  /**
   * Days from a triggering visit's **actual completion date** to its HR follow-up (SRS FR-S-3.4 —
   * actual, never scheduled). 15 for both ANC-HR and INC-HR.
   */
  fun hrOffsetDays(visitType: VisitCodeType): Int

  /**
   * Latest scheduled date for a family, as days from its anchor; visits past it are dropped rather
   * than generated-and-missed. Only INC has one (DOB + 370). Null means uncapped.
   */
  fun cutoffDays(visitType: VisitCodeType): Int?

  /** When a missed visit of this family escalates to the Supervisor (FR-S-3.5, FR-S-3.6). */
  fun escalationPolicy(visitType: VisitCodeType): EscalationPolicy

  /** Whether a family generates HR follow-ups at all. False for NN — SR-NN-01 routes neonatal
   * critical conditions to the referral flow instead of generating an HR visit. */
  fun supportsHrVisits(visitType: VisitCodeType): Boolean
}
