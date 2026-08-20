package org.armman.sakhi.data.schedule

/**
 * The three distinct answers [GoRulesScheduleAdapter.hrVisit] can give, kept separate on purpose
 * (CR-032 fix, 2026-08-13): a plain nullable return could not tell "the pack ran and decided no HR
 * visit is needed" apart from "the pack couldn't run at all" (no cached rule set yet, the native
 * engine failed, a malformed response) — and those two cases need opposite handling.
 * [VisitScheduleCoordinator.generateHrVisit] trusts [NoVisitNeeded] completely (no fallback, per
 * the rule pack's own contract) but falls back to the Kotlin HR generators on [RuleUnavailable]
 * (or when no adapter is wired at all), exactly like every other visit family already does.
 */
sealed class HrVisitOutcome {

  /**
   * No cached rule, the native engine failed to evaluate, or the response was malformed — the
   * caller should fall back to the Kotlin HR generator, not silently produce no visit. Before this
   * fix, this case and [NoVisitNeeded] were indistinguishable (both a bare `null`), so a Sakhi
   * flagging a high-risk finding before her phone had synced the HR rule pack would silently get
   * no follow-up visit at all.
   */
  object RuleUnavailable : HrVisitOutcome()

  /** The rule pack evaluated successfully and decided no HR visit is needed. A real "no" — trust
   * it completely, never fall back. */
  object NoVisitNeeded : HrVisitOutcome()

  /** The rule pack produced a real HR follow-up visit. */
  data class Generated(val visit: VisitScheduleEntity) : HrVisitOutcome()
}
