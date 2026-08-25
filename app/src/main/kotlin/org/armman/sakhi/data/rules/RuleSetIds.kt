package org.armman.sakhi.data.rules

import org.armman.sakhi.data.schedule.VisitCodeType

/**
 * The GoRules `ruleSetId`s seeded on `develop` for Milestone 3 scheduling (CR-032) and risk
 * grading (offline high-risk rule evaluation CR).
 *
 * Source: rules-service seed data, confirmed by the backend team 2026-08-12 (SCHEDULE) and
 * 2026-08-24 (RISK). Every id below is now fetched the same way — [RuleSetApi.getPublishedVersionId]
 * resolves "whatever is currently published" for a given set, then [RuleSetApi.getRuleVersionContent]
 * fetches that version's `rulesJson`, both via [RuleSetRepository.getPublishedRuleSet]. Neither
 * category needs a separate fixed-version constant anymore (see the removed `RISK_ANC_VERSION`/
 * `RISK_INFANT_VERSION` history below).
 *
 * ⚠ **Environment risk.** These are fixed values baked into the backend's `seed.ts`, not rows the
 * database generates on its own — they only resolve if that seed script has actually been run on
 * the target environment. Confirmed present on `develop`; SIT/UAT must be verified separately
 * before [org.armman.sakhi.data.schedule.GoRulesRuleSource] is pointed at them (a 404 from
 * [RuleSetApi] on an environment that hasn't seeded these means exactly this).
 *
 * There is also a legacy placeholder `SCHEDULE` rule set (`11111111-.../22222222-...`,
 * `versionNo v1-hardcoded`) that predates this design — its `rulesJson` is just a note that
 * scheduling logic still lives in [org.armman.sakhi.data.schedule.HardcodedRuleSource]. It is
 * unrelated to the seven real packs below and must never be wired to anything.
 *
 * ✅ **Resolved 2026-08-24 — the ADMIN-only issue and the RISK no-rulesJson stub are both fixed.**
 * Previously `RuleSetApi.getPublishedVersion` (`GET /admin/rules/:setId`) was ADMIN-only, so every
 * SCHEDULE id below 403'd for SAKHI; and RISK's fetch-by-version-id call
 * (`GET /rules/versions/:versionId`) deliberately never returned `rulesJson`. Both were confirmed
 * against a live backend instance and replaced by [RuleSetApi]'s three current endpoints — see
 * that interface's class doc for the full contract. No caller needs to special-case SCHEDULE vs
 * RISK anymore; both fetch through [RuleSetRepository.getPublishedRuleSet] identically.
 */
object RuleSetIds {
  const val ANC = "33333333-3333-4333-8333-333333333331"
  const val PP = "33333333-3333-4333-8333-333333333341"
  const val NN = "33333333-3333-4333-8333-333333333351"
  const val INC = "33333333-3333-4333-8333-333333333361"
  const val CCV = "33333333-3333-4333-8333-333333333371"

  /**
   * HR (high-risk follow-up scheduling) is its own standalone rule set — it is **not** embedded
   * inside the ANC or INC rule packs. ANC-HR and INC-HR follow-up visits must call this set's id,
   * not expect HR logic to live inside [ANC]'s or [INC]'s graph.
   */
  const val HR = "33333333-3333-4333-8333-333333333381"

  /**
   * Delivery-combined-visit scheduling. Seeded alongside the other six, but this milestone task
   * (CR-032) only covers ANC/PP/NN/INC/CCV/HR — [DELIVERY] belongs to the separate delivery-event-
   * session task and is included here only so the id is in one place once that work needs it.
   */
  const val DELIVERY = "33333333-3333-4333-8333-333333333391"

  /**
   * Escalation policy pack (CR-032). Confirmed by the backend team 2026-08-13 to live at its
   * own id, NOT in the 33333333-... schedule-pack family. Nothing in this app calls it yet.
   */
  const val ESCALATION = "44444444-4444-4444-8444-444444444441"

  /**
   * Mother/ANC clinical risk grading (offline high-risk rule evaluation CR). Rule set
   * `55555555-...551`. Confirmed live and wired server-side to `ANC_VISIT` on dev 2026-08-24.
   *
   * Fetched the same way as [ANC]/[PP]/etc. above via [RuleSetRepository.getPublishedRuleSet] —
   * as of 2026-08-24 this resolves "whatever's newly published" automatically, the same as every
   * SCHEDULE id. (Previously this app fetched RISK by a fixed version-id constant instead, because
   * the only non-admin endpoint available at the time couldn't resolve "latest for a set" — that
   * endpoint never actually returned `rulesJson` either, so on-device RISK grading was silently
   * broken the whole time. Both limitations are resolved; see [RuleSetApi]'s class doc.)
   */
  const val RISK_ANC = "55555555-5555-4555-8555-555555555551"

  /**
   * Infant/neonatal clinical risk grading (offline high-risk rule evaluation CR). Rule set
   * `55555555-...561`. Confirmed live and wired server-side to
   * `INFANT_VISIT`/`INC_VISIT`/`CCV_VISIT`/`NEONATAL_VISIT` on dev 2026-08-24.
   *
   * Same fetch path and history as [RISK_ANC] — see that constant's doc.
   */
  const val RISK_INFANT = "55555555-5555-4555-8555-555555555561"

  /**
   * Maps a [VisitCodeType] family to the rule set that schedules it. HR variants
   * ([VisitCodeType.ANC_HR], [VisitCodeType.INC_HR], [VisitCodeType.CCV_HR]) all resolve to [HR] —
   * per this object's doc, HR is evaluated as its own rule regardless of which family triggered it.
   * [VisitCodeType.ANC_POST_EDD] resolves to [ANC] — the post-EDD visit is authored as part of the
   * ANC rule pack's decision graph, not a separate set.
   */
  fun forVisitType(visitType: VisitCodeType): String = when (visitType) {
    VisitCodeType.ANC, VisitCodeType.ANC_POST_EDD -> ANC
    VisitCodeType.PP -> PP
    VisitCodeType.NN -> NN
    VisitCodeType.INC -> INC
    VisitCodeType.CCV -> CCV
    VisitCodeType.ANC_HR, VisitCodeType.INC_HR, VisitCodeType.CCV_HR -> HR
    VisitCodeType.DELIVERY -> DELIVERY
  }
}
