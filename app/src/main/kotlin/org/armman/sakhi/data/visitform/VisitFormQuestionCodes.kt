package org.armman.sakhi.data.visitform

import org.armman.sakhi.R
import org.armman.sakhi.data.forms.FormAnswers

/**
 * `question_code`/`value_code`s the ANC_VISIT dynamic schema uses for the fields FR-S-4.4's
 * critical-condition rule reads, and the LMP/anthropometry fields the visit-specific computed
 * evaluator needs. Verified against the live `GET /forms/ANC_VISIT/active-version` v1 response
 * (2026-08-07) — same "treat as a contract with the backend, not a label" caution as
 * [org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes].
 *
 * INFANT_VISIT has no equivalent object yet: no clinical danger-sign combination rule for the
 * infant flow has been confirmed with ARMMAN (unlike FR-S-4.4 for the mother flow, which this
 * file ports from the retired hand-coded [VisitDataState]), so nothing here is reused there.
 */
object VisitFormQuestionCodes {
  /** Q "Date of visit" — first field in both ANC_VISIT and INFANT_VISIT. Auto-filled with today's
   * date on load (see [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.load]) and shown
   * in dd-mm-yyyy, not the app's usual "dd MMM yyyy" (bharath, 2026-08-07: this field only). */
  const val DATE_OF_VISIT = "date_of_visit"

  /** Q "Actual visit date" — the spec's own field name for the same "auto-select today's date,
   * mandatory" requirement on POSTPARTUM_VISIT (PP I-IV) and NEONATAL_VISIT (NN1/NN2), which this
   * codebase has not yet captured a live schema for (unlike [DATE_OF_VISIT]'s confirmed
   * ANC_VISIT/INFANT_VISIT payload). Kept as a distinct constant rather than assumed identical to
   * [DATE_OF_VISIT], since the two visit-form families were built independently and a schema diff
   * (like `registrtion_date` vs `registration_date` elsewhere in this codebase) is the more likely
   * failure mode than the prefill logic itself being wrong. */
  const val ACTUAL_VISIT_DATE = "actual_visit_date"

  /** Every question_code today's-date auto-fill should try, across every visit-form family. Mirrors
   * [org.armman.sakhi.data.forms.REGISTRATION_DATE_QUESTION_CODES]'s defensive multi-spelling
   * pattern: [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.prefillDefaultVisitDate] only
   * ever writes codes the ACTIVE schema actually declares, so listing both spellings here is safe
   * for every form type regardless of which one it turns out to use. */
  val VISIT_DATE_QUESTION_CODES: Set<String> = setOf(DATE_OF_VISIT, ACTUAL_VISIT_DATE)

  const val BLOOD_PRESSURE_SYSTOLIC = "blood_pressure_bp_systolic"
  const val BLOOD_PRESSURE_DIASTOLIC = "blood_pressure_bp_diastolic"
  const val HAEMOGLOBIN = "haemoglobin_hb_g_dl"
  const val BLOOD_GLUCOSE = "blood_glucose_in_mg_dl"

  /**
   * The remaining ANC_VISIT clinical-risk-grading inputs the mother/ANC GoRules pack
   * ([org.armman.sakhi.data.rules.RuleSetIds.RISK_ANC]) reads — confirmed against a live
   * `GET /forms/ANC_VISIT/active-version` dump 2026-08-24 (not just the API reference the earlier
   * constants above were verified against; same form, later confirmation pass, all consistent).
   * [GESTATIONAL_WEIGHT_GAIN]'s `question_code` matches
   * [org.armman.sakhi.data.visitform.VisitFormComputedFieldEvaluator]'s `GESTATIONAL_WEIGHT_GAIN`
   * `computedFrom` token exactly — same field, now confirmed by name as well as by token.
   */
  const val MID_UPPER_ARM_CIRCUMFERENCE_CM = "mid_upper_arm_circumference_in_cm"
  const val BODY_TEMPERATURE_F = "body_temperature_in_f"
  const val FETAL_HEART_RATE = "fetal_heart_rate"
  const val GESTATIONAL_WEIGHT_GAIN = "gestational_weight_gain"
  const val CHECK_PALM_AND_NAILS = "check_palm_and_nails"
  const val CHECK_SCLERA_EYES = "check_sclera_eyes"
  const val CHECK_SKIN = "check_skin"
  const val URINE_TEST = "urine_test"

  /**
   * The two raw ANC_VISIT fields backend's updated ANC risk pack will derive `fundalHeightDeviationCm`
   * from itself (confirmed 2026-08-24: `fundal_height_in_cm - current_gestational_age_in_weeks`,
   * the standard obstetric GA-in-weeks-approximates-fundal-height-in-cm approximation) — both
   * confirmed numeric, on `ANC_VISIT`, against the same live schema dump. Sent by
   * [AncRiskAnswerMapper] ahead of that pack update landing (harmless extra field the current pack
   * simply doesn't read yet, same "partial input is safe" contract every other field here relies
   * on) so real-time on-device highlighting picks up FUNDAL_HEIGHT the moment the new pack version
   * is fetched — no second app change needed once backend ships it.
   */
  const val FUNDAL_HEIGHT_CM = "fundal_height_in_cm"
  const val CURRENT_GESTATIONAL_AGE_WEEKS = "current_gestational_age_in_weeks"

  /** Q "Have you been experiencing any of these since the last visit?" — multiselect danger signs. */
  const val DANGER_SIGNS = "have_you_been_experiencing_any_of_these_since_the_last_visit"

  /** Q "Swelling" — multiselect. */
  const val SWELLING = "swelling"

  /** Q "LMP" — the Sakhi's initial entry. See [VisitFormComputedFieldEvaluator] for why
   * [LMP_DATE_EDIT] takes priority over this once a sonography report confirms it. */
  const val LMP = "lmp"

  /** Q "LMP Date" — only rendered/answerable once
   * `do_you_have_a_sonography_report_to_confirm_the_lmp_date` is "yes"; the sonography-confirmed
   * value, more accurate than [LMP] once present. */
  const val LMP_DATE_EDIT = "lmp_date_edit"

  /** Q "Upload sonography report image" — the live schema renders this alongside [LMP_DATE_EDIT]
   * (same `visibleWhen`, both mandatory once sonography = Yes), with no enforced ordering between
   * them. [org.armman.sakhi.data.visitform.VisitFormComputedFieldEvaluator.isLmpDateEditLocked]
   * uses this to lock [LMP_DATE_EDIT] until this image is actually captured — confirmed against the
   * live `GET /forms/ANC_VISIT/active-version` response (bharath, 2026-09-01), not guessed. */
  const val UPLOAD_SONOGRAPHY_REPORT_IMAGE = "upload_sonography_report_image"

  const val HEIGHT_CM = "height_of_the_woman_in_cm"
  const val WEIGHT_KG = "current_weight_of_the_woman_in_kg"

  /** Bug fix (found in manual QA, 2026-08-21): POSTPARTUM_VISIT's own weight field is
   * `current_weight_kg` — a DIFFERENT question_code from ANC_VISIT's [WEIGHT_KG]
   * (`current_weight_of_the_woman_in_kg`), confirmed against the live `postpartum-visit.json`
   * schema. [VisitFormComputedFieldEvaluator]'s BMI formula only ever looked up [WEIGHT_KG], so
   * "Current BMI" silently never computed on PP1 even once routed there — same
   * multi-spelling-resilience pattern as [org.armman.sakhi.data.forms.REGISTRATION_DATE_QUESTION_CODES]
   * and [VISIT_DATE_QUESTION_CODES] above. NOTE: POSTPARTUM_VISIT also uses `body_temperature_f`/
   * `current_muac_cm` for its own temperature/MUAC fields — different codes again from
   * [BODY_TEMPERATURE_F]/[MID_UPPER_ARM_CIRCUMFERENCE_CM] above, confirmed in the same 2026-08-24
   * schema dump. Not wired anywhere yet since PP isn't one of the forms risk grading is linked to
   * for this CR — flagging so nobody assumes ANC's spellings carry over if that ever changes. */
  val WEIGHT_KG_QUESTION_CODES: Set<String> = setOf(WEIGHT_KG, "current_weight_kg")

  /** `value_code`s the danger-sign/swelling rules match on. */
  object ValueCode {
    const val DIZZINESS = "dizziness"
    const val BREATHLESSNESS = "breathlessness"
    const val NO_ABNORMAL_SIGNS_AND_SYMPTOMS = "no_abnormal_signs_and_symptoms"
    const val NO_SWELLING = "no_swelling"
  }
}

/**
 * FR-S-4.4 critical-condition detection, ported from the retired hand-coded
 * [VisitDataState]'s `hasAnyDangerSign`/`hasCriticalHypertension`/`hasCriticalAnaemia`/
 * `hasCriticalHypoglycaemia` getters onto the dynamic ANC_VISIT [FormAnswers] shape — same
 * thresholds, same evaluation priority order (a beneficiary with any danger sign is flagged for
 * that alone, before the BP/Hb/glucose combinations are even checked), just re-keyed from the old
 * 1-based Excel option codes to the schema's real `value_code`s (see
 * [VisitFormQuestionCodes.ValueCode]'s doc and the mapping notes below).
 *
 * Mapping notes (old 1-based S.No code -> new `value_code`, both confirmed against the live
 * schema's `sort_order`): danger sign 7 (Dizziness) -> `dizziness` (sort_order 6); danger sign 8
 * (Breathlessness) -> `breathlessness` (sort_order 7); danger sign 13 ("No abnormal signs and
 * symptoms") -> `no_abnormal_signs_and_symptoms` (sort_order 12); swelling 1 ("No swelling") ->
 * `no_swelling` (sort_order 0).
 *
 * The hypertension threshold is a strict `>`, not `>=`, deliberately matching
 * [VisitDataState.hasCriticalHypertension]'s actual comparison exactly (some project docs
 * describe the spec's intent as "≥160/≥110" — this ports the shipped code, not the prose).
 */
object VisitCriticalConditionEvaluator {

  /** Q23 severe-hypertension thresholds (systolic/diastolic), strict `>`. */
  const val BP_SYSTOLIC_CRITICAL = 160.0
  const val BP_DIASTOLIC_CRITICAL = 110.0

  /** Q26 severe-anaemia threshold. */
  const val HB_CRITICAL = 7.0

  /** Q28 hypoglycaemia threshold. */
  const val GLUCOSE_CRITICAL_LOW = 70.0

  /** The danger signs the Excel pairs with a severe BP/Hb reading for the critical pathway. */
  private val DANGER_SIGNS_SEVERE_ACCOMPANYING = setOf(
    VisitFormQuestionCodes.ValueCode.DIZZINESS,
    VisitFormQuestionCodes.ValueCode.BREATHLESSNESS,
  )

  /** Closest listed proxy for "cold sweat" (Q28's critical note) — Dizziness, same as before. */
  private val DANGER_SIGNS_COLD_SWEAT = setOf(VisitFormQuestionCodes.ValueCode.DIZZINESS)

  /** The first matching critical condition, in the same priority order the retired ViewModel's
   * `checkCriticalConditions()` used, or null if none applies. */
  fun evaluate(answers: FormAnswers): CriticalCondition? = when {
    hasAnyDangerSign(answers) -> CriticalCondition(R.string.visit_form_critical_danger_sign)
    hasCriticalHypertension(answers) -> CriticalCondition(R.string.visit_form_critical_hypertension)
    hasCriticalAnaemia(answers) -> CriticalCondition(R.string.visit_form_critical_anaemia)
    hasCriticalHypoglycaemia(answers) -> CriticalCondition(R.string.visit_form_critical_hypoglycaemia)
    else -> null
  }

  /** Any danger sign other than "No abnormal signs and symptoms" — immediate stop-and-refer. */
  private fun hasAnyDangerSign(answers: FormAnswers): Boolean =
    answers.multiValueOf(VisitFormQuestionCodes.DANGER_SIGNS)
      .any { it != VisitFormQuestionCodes.ValueCode.NO_ABNORMAL_SIGNS_AND_SYMPTOMS }

  /** Severe BP (systolic > 160 or diastolic > 110) plus a severe-accompanying danger sign or any
   * swelling. */
  private fun hasCriticalHypertension(answers: FormAnswers): Boolean {
    val systolic = answers.valueOf(VisitFormQuestionCodes.BLOOD_PRESSURE_SYSTOLIC)?.toDoubleOrNull() ?: return false
    val diastolic = answers.valueOf(VisitFormQuestionCodes.BLOOD_PRESSURE_DIASTOLIC)?.toDoubleOrNull() ?: return false
    val severe = systolic > BP_SYSTOLIC_CRITICAL || diastolic > BP_DIASTOLIC_CRITICAL
    if (!severe) return false
    val dangerSigns = answers.multiValueOf(VisitFormQuestionCodes.DANGER_SIGNS)
    val swelling = answers.multiValueOf(VisitFormQuestionCodes.SWELLING)
    val hasSymptom = dangerSigns.any { it in DANGER_SIGNS_SEVERE_ACCOMPANYING } ||
      swelling.any { it != VisitFormQuestionCodes.ValueCode.NO_SWELLING }
    return hasSymptom
  }

  /** Hb < 7.0 plus a severe-accompanying danger sign. */
  private fun hasCriticalAnaemia(answers: FormAnswers): Boolean {
    val hb = answers.valueOf(VisitFormQuestionCodes.HAEMOGLOBIN)?.toDoubleOrNull() ?: return false
    if (hb >= HB_CRITICAL) return false
    return answers.multiValueOf(VisitFormQuestionCodes.DANGER_SIGNS).any { it in DANGER_SIGNS_SEVERE_ACCOMPANYING }
  }

  /** Blood glucose < 70 plus dizziness/cold sweat. */
  private fun hasCriticalHypoglycaemia(answers: FormAnswers): Boolean {
    val glucose = answers.valueOf(VisitFormQuestionCodes.BLOOD_GLUCOSE)?.toDoubleOrNull() ?: return false
    if (glucose >= GLUCOSE_CRITICAL_LOW) return false
    return answers.multiValueOf(VisitFormQuestionCodes.DANGER_SIGNS).any { it in DANGER_SIGNS_COLD_SWEAT }
  }
}
