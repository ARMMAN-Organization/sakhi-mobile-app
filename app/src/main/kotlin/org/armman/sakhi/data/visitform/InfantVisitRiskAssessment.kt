package org.armman.sakhi.data.visitform

import org.armman.sakhi.data.beneficiary.RiskLevel
import org.armman.sakhi.data.forms.FormAnswers

/**
 * `question_code`/`value_code`s the INFANT_VISIT dynamic schema uses for the fields
 * [InfantVisitRiskAssessment] reads. Verified against the live `GET /forms/INFANT_VISIT
 * /active-version` v1 response (2026-08-08) — same "treat as a contract with the backend, not a
 * label" caution as [VisitFormQuestionCodes] (the mother/ANC_VISIT equivalent). No relation to
 * that object — INFANT_VISIT's schema is entirely separate from ANC_VISIT's.
 */
object InfantVisitFormQuestionCodes {
  /** Q "Is the baby showing any danger signs since last visit?" — multiselect. */
  const val DANGER_SIGNS = "is_the_baby_showing_any_danger_signs_since_last_visit"

  /** Q "Nutritional status (Wasting)" — `computedFrom: NUTRITIONAL_ZSCORE`, see
   * [InfantVisitFormComputedFieldEvaluator]'s doc for why this is currently always unanswered. */
  const val NUTRITIONAL_STATUS_WASTING = "nutritional_status_wasting"

  /** Q "Nutritional status (Stunting)" — same `NUTRITIONAL_ZSCORE` caveat as [NUTRITIONAL_STATUS_WASTING]. */
  const val NUTRITIONAL_STATUS_STUNTING = "nutritional_status_stunting"

  /** Q "Nutritional status (Underweight)" — same `NUTRITIONAL_ZSCORE` caveat as [NUTRITIONAL_STATUS_WASTING]. */
  const val NUTRITIONAL_STATUS_UNDERWEIGHT = "nutritional_status_underweight"

  /** Q "Is there any deformity detected in the infant?" */
  const val DEFORMITY = "is_there_any_deformity_detected_in_the_infant"

  /** Q "Premature child". */
  const val PREMATURE_CHILD = "premature_child"

  /** Q "Activity level". */
  const val ACTIVITY_LEVEL = "activity_level"

  /** Q "Is the Child showing all Developmental Milestones as per his/her age?" */
  const val MILESTONES_AS_PER_AGE = "is_the_child_showing_all_developmental_milestones_as_per_his_her_age"

  /** Q "Feeding concerns" — multiselect. */
  const val FEEDING_CONCERNS = "feeding_concerns"

  /** `value_code`s [InfantVisitRiskAssessment] matches on — the "no risk" sentinel for each
   * multiselect/select field, verified against the same live schema response. */
  object ValueCode {
    const val NO_ABNORMAL_SIGNS_SYMPTOMS = "no_abnormal_signs_symptoms"
    const val NO_FEEDING_CONCERNS = "no_concerns"
    const val DEFORMITY_YES = "yes"
    const val PRETERM = "preterm_lt_37_weeks"
    const val ACTIVITY_REDUCED_MOVEMENT = "reduced_movement"
    const val ACTIVITY_LETHARGIC = "lethargic"
    const val MILESTONES_NO = "no"
    const val NUTRITION_MAM = "mam"
    const val NUTRITION_SAM = "sam"
    const val NUTRITION_MODERATELY_STUNTED = "moderately_stunted"
    const val NUTRITION_SEVERELY_STUNTED = "severely_stunted"
    const val NUTRITION_MUW = "muw"
    const val NUTRITION_SUW = "suw"
  }
}

/** One "known risk" chip on the INFANT_VISIT Summary tab — the infant counterpart to
 * [VisitFormRiskFinding]. [label] is the exact wording shown to the Sakhi: the live schema's own
 * option label for a selected danger sign/feeding concern (kept as a local copy in
 * [InfantVisitRiskAssessment] rather than re-resolved from the schema's options list, since the
 * code->label pairs are already a verified contract per [InfantVisitFormQuestionCodes]'s doc), or
 * a short fixed phrase for a categorical field like nutritional status/prematurity that has no
 * single "the" label of its own. */
data class InfantVisitRiskFinding(val label: String, val riskLevel: RiskLevel)

/**
 * Known-risk detection for the INFANT_VISIT Summary tab (2026-08-08) — the infant counterpart to
 * [VisitFormRiskAssessment]. Deliberately narrower in kind, not just in content: where
 * [VisitFormRiskAssessment] grades numeric vitals (BP/Hb/BMI) against clinical thresholds derived
 * from the SRS, every row here instead surfaces a field the live INFANT_VISIT schema *already*
 * reports as a categorical/computed answer — the danger-signs multiselect, the nutritional-status
 * z-score outputs, the deformity/prematurity/activity-level/milestones/feeding-concerns fields —
 * so no new clinical combination rule is being invented in this file (see
 * [InfantVisitFormQuestionCodes]'s doc; [VisitFormQuestionCodes]'s doc notes explicitly that no
 * such rule for infants has been confirmed with ARMMAN, which is why this stays limited to
 * re-surfacing the schema's own categories rather than attempting anything like
 * [VisitCriticalConditionEvaluator]'s combination logic).
 *
 * [InfantVisitFormComputedFieldEvaluator]'s `NUTRITIONAL_ZSCORE` branch currently always returns
 * null client-side (WHO growth-standard z-score tables aren't available on-device yet — see that
 * evaluator's doc), so the three nutritional-status rows below won't actually fire on any real
 * visit until that's wired up; they're kept here so this list picks the value up automatically
 * the moment it starts arriving, with no further change needed on this side.
 */
object InfantVisitRiskAssessment {

  private val RISK_SEVERITY_ORDER = listOf(RiskLevel.HIGH, RiskLevel.MODERATE, RiskLevel.MILD, RiskLevel.LOW)

  /** Labels ported verbatim from the live schema's danger-signs multiselect options. */
  private val DANGER_SIGN_LABELS: Map<String, String> = mapOf(
    "dry_wet_persistent_cough" to "Dry cough/Wet cough/Persistent cough",
    "fast_breathing_grunting_chest_in_drawing" to "Fast breathing, grunting, chest in-drawing",
    "severe_hypothermia_lt_96f" to "Severe hypothermia (<96°F)",
    "severe_hyperthermia_gt_100f" to "Severe hyperthermia (>100°F)",
    "yellow_eyes_palms_soles_body_face_etc" to "Yellow eyes, palms, soles, body, face",
    "pallor_sclera_palms_soles_etc_13_24_months_only" to "Pallor — sclera, palms, soles",
    "blue_color_cyanosis_around_lips_skin" to "Blue color (cyanosis) around lips/skin",
    "presence_of_oedema_pitting" to "Presence of oedema (pitting)",
    "convulsions_twitching_fits_or_abnormal_movements" to "Convulsions, twitching, fits, or abnormal movements",
    "lethargy_floppiness_or_inability_to_wake_up" to "Lethargy, floppiness, or inability to wake up",
    "not_able_to_drink_feed" to "Not able to drink/feed",
    "vomits_everything" to "Vomits everything",
    "diarrhoea" to "Diarrhoea",
    "blood_in_stool" to "Blood in stool",
  )

  /** Labels ported verbatim from the live schema's feeding-concerns multiselect options. */
  private val FEEDING_CONCERN_LABELS: Map<String, String> = mapOf(
    "mother_not_alive" to "Mother not alive",
    "mother_has_cracked_inverted_nipples" to "Mother has cracked/inverted nipples",
    "baby_not_latching_properly" to "Baby not latching properly",
    "low_milk_supply" to "Low milk supply",
    "breast_engorgement_or_swelling" to "Breast engorgement or swelling",
    "baby_refusing_to_feed" to "Baby refusing to feed",
    "other" to "Other feeding concern",
  )

  /** Worst-of aggregation - HIGH > MODERATE > MILD > LOW; empty list is LOW. Same rule as
   * [VisitFormRiskAssessment.overall]. */
  fun overall(levels: List<RiskLevel>): RiskLevel = RISK_SEVERITY_ORDER.firstOrNull { it in levels } ?: RiskLevel.LOW

  private fun List<InfantVisitRiskFinding>.sortedByRisk(): List<InfantVisitRiskFinding> =
    sortedBy { RISK_SEVERITY_ORDER.indexOf(it.riskLevel) }

  /** Every known risk this visit's answers indicate, worst-first; empty when nothing risk-worthy
   * has been recorded yet (including "no beneficiary met" visits, which never populate any of the
   * fields read here). */
  fun buildKnownRisks(answers: FormAnswers): List<InfantVisitRiskFinding> = buildList {
    answers.multiValueOf(InfantVisitFormQuestionCodes.DANGER_SIGNS)
      .filter { it != InfantVisitFormQuestionCodes.ValueCode.NO_ABNORMAL_SIGNS_SYMPTOMS }
      .forEach { code -> add(InfantVisitRiskFinding(DANGER_SIGN_LABELS[code] ?: code, RiskLevel.HIGH)) }

    answers.multiValueOf(InfantVisitFormQuestionCodes.FEEDING_CONCERNS)
      .filter { it != InfantVisitFormQuestionCodes.ValueCode.NO_FEEDING_CONCERNS }
      .forEach { code -> add(InfantVisitRiskFinding(FEEDING_CONCERN_LABELS[code] ?: code, RiskLevel.MILD)) }

    when (answers.valueOf(InfantVisitFormQuestionCodes.NUTRITIONAL_STATUS_WASTING)) {
      InfantVisitFormQuestionCodes.ValueCode.NUTRITION_SAM ->
        add(InfantVisitRiskFinding("Severe acute malnutrition (SAM)", RiskLevel.HIGH))
      InfantVisitFormQuestionCodes.ValueCode.NUTRITION_MAM ->
        add(InfantVisitRiskFinding("Moderate acute malnutrition (MAM)", RiskLevel.MODERATE))
    }
    when (answers.valueOf(InfantVisitFormQuestionCodes.NUTRITIONAL_STATUS_STUNTING)) {
      InfantVisitFormQuestionCodes.ValueCode.NUTRITION_SEVERELY_STUNTED ->
        add(InfantVisitRiskFinding("Severely stunted", RiskLevel.HIGH))
      InfantVisitFormQuestionCodes.ValueCode.NUTRITION_MODERATELY_STUNTED ->
        add(InfantVisitRiskFinding("Moderately stunted", RiskLevel.MODERATE))
    }
    when (answers.valueOf(InfantVisitFormQuestionCodes.NUTRITIONAL_STATUS_UNDERWEIGHT)) {
      InfantVisitFormQuestionCodes.ValueCode.NUTRITION_SUW ->
        add(InfantVisitRiskFinding("Severely underweight (SUW)", RiskLevel.HIGH))
      InfantVisitFormQuestionCodes.ValueCode.NUTRITION_MUW ->
        add(InfantVisitRiskFinding("Moderately underweight (MUW)", RiskLevel.MODERATE))
    }

    if (answers.valueOf(InfantVisitFormQuestionCodes.DEFORMITY) == InfantVisitFormQuestionCodes.ValueCode.DEFORMITY_YES) {
      add(InfantVisitRiskFinding("Deformity detected", RiskLevel.MODERATE))
    }
    if (answers.valueOf(InfantVisitFormQuestionCodes.PREMATURE_CHILD) == InfantVisitFormQuestionCodes.ValueCode.PRETERM) {
      add(InfantVisitRiskFinding("Preterm birth (<37 weeks)", RiskLevel.MILD))
    }
    when (answers.valueOf(InfantVisitFormQuestionCodes.ACTIVITY_LEVEL)) {
      InfantVisitFormQuestionCodes.ValueCode.ACTIVITY_LETHARGIC ->
        add(InfantVisitRiskFinding("Lethargic activity level", RiskLevel.HIGH))
      InfantVisitFormQuestionCodes.ValueCode.ACTIVITY_REDUCED_MOVEMENT ->
        add(InfantVisitRiskFinding("Reduced movement", RiskLevel.MILD))
    }
    if (answers.valueOf(InfantVisitFormQuestionCodes.MILESTONES_AS_PER_AGE) ==
      InfantVisitFormQuestionCodes.ValueCode.MILESTONES_NO
    ) {
      add(InfantVisitRiskFinding("Not showing developmental milestones for age", RiskLevel.MODERATE))
    }
  }.sortedByRisk()
}
