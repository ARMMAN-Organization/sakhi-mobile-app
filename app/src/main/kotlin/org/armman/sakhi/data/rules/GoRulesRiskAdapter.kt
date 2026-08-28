package org.armman.sakhi.data.rules

import android.util.Log
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SakhiSync"

/**
 * Calls the ANC/Infant clinical-risk-grading GoRules packs fully on-device and parses their
 * response — the risk-grading counterpart to [org.armman.sakhi.data.schedule.GoRulesScheduleAdapter].
 *
 * Deliberately narrow, same reasoning as that class: this is the *only* place in the app that
 * knows the two risk packs' request/response shape. Building the [answers] input from a form's
 * live field values (mapping each `question_code`/`value_code` to the field names the packs
 * expect — e.g. `blood_pressure_bp_systolic`, `haemoglobin_hb_g_dl`) is the caller's job, same
 * split as [org.armman.sakhi.data.schedule.GoRulesScheduleAdapter] leaving `ScheduleContext`
 * construction to its own callers.
 *
 * ### Known limitation — `isFirstInstance`/`consecutiveNoImprovementCount` default to empty
 * Both packs accept these as caller-supplied history (per condition code) to correctly apply
 * "referral only on first instance" / "no-improvement streak" rules (Appendix D). Real values
 * require scanning this beneficiary's prior visit/risk history — not built yet (flagged in the
 * risk-rule-pack offline-readiness findings, 2026-08-24). An empty map is the pack's documented
 * safe default (never throws), but it means every on-device evaluation is treated as "first
 * instance, no prior streak" even for a beneficiary on her 3rd occurrence — a real correctness
 * gap versus the server-side path (which does scan real history), not just a missing feature.
 * Do not treat on-device HR/referral-trigger results as authoritative until this is resolved;
 * the server-side [RuleSetIds.RISK_ANC]/[RuleSetIds.RISK_INFANT] evaluation after sync remains
 * the source of truth for anything gated by first-instance/no-improvement logic.
 */
@Singleton
class GoRulesRiskAdapter @Inject constructor(
  private val ruleSetRepository: RuleSetRepository,
  private val ruleEvaluator: RuleEvaluator,
) {

  /**
   * Grades whatever ANC fields are present in [answers] against the mother/ANC risk pack.
   * [answers] should already be keyed by the pack's expected field names (not raw question
   * codes) — partial input is safe (see [RuleEvaluator]/pack doc: an ungraded field is simply
   * absent from the response, not a false "NORMAL").
   *
   * @param isFirstInstance per-condition-code override; empty by default — see this class's doc
   * for why that's a known limitation, not a full substitute for real history.
   */
  suspend fun gradeAncRisk(
    answers: JsonObject,
    isFirstInstance: Map<String, Boolean> = emptyMap(),
  ): RiskGradingResult? = grade(
    ruleSetId = RuleSetIds.RISK_ANC,
    conditionIds = RiskConditionIds.ANC,
    answers = answers,
    isFirstInstance = isFirstInstance,
    consecutiveNoImprovementCount = emptyMap(),
    logContext = "gradeAncRisk",
  )

  /**
   * Grades whatever infant fields are present in [answers] against the infant/neonatal risk
   * pack. Same partial-input and field-naming contract as [gradeAncRisk].
   */
  suspend fun gradeInfantRisk(
    answers: JsonObject,
    isFirstInstance: Map<String, Boolean> = emptyMap(),
    consecutiveNoImprovementCount: Map<String, Int> = emptyMap(),
  ): RiskGradingResult? = grade(
    ruleSetId = RuleSetIds.RISK_INFANT,
    conditionIds = RiskConditionIds.INFANT,
    answers = answers,
    isFirstInstance = isFirstInstance,
    consecutiveNoImprovementCount = consecutiveNoImprovementCount,
    logContext = "gradeInfantRisk",
  )

  private suspend fun grade(
    ruleSetId: String,
    conditionIds: Map<String, String>,
    answers: JsonObject,
    isFirstInstance: Map<String, Boolean>,
    consecutiveNoImprovementCount: Map<String, Int>,
    logContext: String,
  ): RiskGradingResult? {
    val cached = ruleSetRepository.getPublishedRuleSet(ruleSetId) ?: run {
      Log.w(TAG, "GoRulesRiskAdapter.$logContext: no cached rule pack for set $ruleSetId")
      return null
    }
    val context = answers.deepCopy().apply {
      add("conditionIds", conditionIds.toJsonObject { it })
      add("isFirstInstance", isFirstInstance.toJsonObject { v -> v })
      if (consecutiveNoImprovementCount.isNotEmpty()) {
        add("consecutiveNoImprovementCount", consecutiveNoImprovementCount.toJsonObject { v -> v })
      }
    }
    val response = try {
      ruleEvaluator.evaluate(cached.rulesJson, context)
    } catch (e: Throwable) {
      // Throwable, not Exception — see ZenRuleEvaluator.evaluate's catch for why (native/JNI
      // linkage failures surface as Error, not Exception, and this call site fires on every
      // keystroke in the ANC visit form once "met beneficiary" is Yes).
      Log.w(TAG, "GoRulesRiskAdapter.$logContext: evaluate threw ${e::class.simpleName} — ${e.message}")
      null
    } ?: return null
    return parseResult(response, logContext)
  }

  private fun parseResult(response: JsonObject, logContext: String): RiskGradingResult? {
    val categoryRaw = response.get("overallRiskCategory")?.takeUnless { it.isJsonNull }?.asString
    val category = categoryRaw?.let {
      runCatching { RiskCategory.valueOf(it) }.getOrElse {
        Log.w(TAG, "GoRulesRiskAdapter.$logContext: unrecognized overallRiskCategory \"$categoryRaw\"")
        RiskCategory.UNKNOWN
      }
    } ?: RiskCategory.UNKNOWN

    val conditionsArray = response.getAsJsonArray("conditions") ?: run {
      Log.w(TAG, "GoRulesRiskAdapter.$logContext: response had no 'conditions' array")
      return RiskGradingResult(category, emptyList())
    }
    val findings = conditionsArray.mapNotNull { element ->
      val obj = element.asJsonObject
      val conditionId = obj.get("riskConditionId")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
      val gradeRaw = obj.get("grade")?.takeUnless { it.isJsonNull }?.asString
      val grade = gradeRaw?.let {
        runCatching { RiskGrade.valueOf(it) }.getOrElse { RiskGrade.UNKNOWN }
      } ?: RiskGrade.UNKNOWN
      RiskConditionFinding(
        riskConditionId = conditionId,
        grade = grade,
        gradeRank = obj.get("gradeRank")?.takeUnless { it.isJsonNull }?.asInt ?: 0,
        isReferralTrigger = obj.get("isReferralTrigger")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
        isEducationTrigger = obj.get("isEducationTrigger")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
        isHrVisitTrigger = obj.get("isHrVisitTrigger")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
      )
    }
    return RiskGradingResult(category, findings)
  }

  private fun <T> Map<String, T>.toJsonObject(valueToJson: (T) -> Any): JsonObject = JsonObject().apply {
    this@toJsonObject.forEach { (key, value) ->
      when (val v = valueToJson(value)) {
        is String -> addProperty(key, v)
        is Boolean -> addProperty(key, v)
        is Int -> addProperty(key, v)
        else -> error("GoRulesRiskAdapter.toJsonObject: unsupported value type ${v::class.simpleName}")
      }
    }
  }
}
