package org.armman.sakhi.data.rules

/**
 * Output shape common to both risk-grading GoRules packs ([RuleSetIds.RISK_ANC] /
 * [RuleSetIds.RISK_INFANT]), confirmed against `anc-risk.rulesJson.ts` /
 * `infant-risk.rulesJson.ts` 2026-08-24:
 * ```json
 * {
 *   "overallRiskCategory": "NORMAL"|"LOW"|"HIGH"|"CRITICAL",
 *   "conditions": [{
 *     "riskConditionId": "<uuid>", "grade": "NORMAL"|"MILD"|"MODERATE"|"SEVERE", "gradeRank": 0-3,
 *     "isReferralTrigger": bool, "isEducationTrigger": bool, "isHrVisitTrigger": bool,
 *     "observedValueJson": {...}
 *   }]
 * }
 * ```
 * A field simply absent from [conditions] means "not evaluated" (e.g. the input wasn't filled in
 * yet during real-time per-field evaluation) — NOT the same as a `NORMAL` grade. Callers must not
 * collapse "no finding yet" into "found to be normal."
 */
data class RiskGradingResult(
  val overallRiskCategory: RiskCategory,
  val conditions: List<RiskConditionFinding>,
)

data class RiskConditionFinding(
  val riskConditionId: String,
  val grade: RiskGrade,
  val gradeRank: Int,
  val isReferralTrigger: Boolean,
  val isEducationTrigger: Boolean,
  val isHrVisitTrigger: Boolean,
)

enum class RiskCategory { NORMAL, LOW, HIGH, CRITICAL, UNKNOWN }

enum class RiskGrade { NORMAL, MILD, MODERATE, SEVERE, UNKNOWN }
