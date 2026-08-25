package org.armman.sakhi.data.visitform

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.armman.sakhi.data.forms.FormAnswers

/**
 * Maps ANC_VISIT [FormAnswers] into the `answers` input the [RuleSetIds.RISK_ANC]/
 * [org.armman.sakhi.data.rules.GoRulesRiskAdapter.gradeAncRisk] GoRules pack expects.
 *
 * Partial by design — call this after every relevant field change for real-time evaluation, not
 * only at submission; a field not yet answered is simply omitted from the returned object, which
 * [org.armman.sakhi.data.rules.GoRulesRiskAdapter]'s "partial input is safe" contract handles
 * correctly (an ungraded field is absent from the pack's response, not falsely graded NORMAL).
 *
 * ### Fields wired below — every field the ANC risk pack reads now has a confirmed
 * `question_code`, verified against a live `GET /forms/ANC_VISIT/active-version` dump
 * (2026-08-24): `blood_pressure_bp_systolic`, `blood_pressure_bp_diastolic`,
 * `haemoglobin_hb_g_dl`, `height_of_the_woman_in_cm`, `bmi` (computed), `blood_glucose_in_mg_dl`,
 * `have_you_been_experiencing_any_of_these_since_the_last_visit`,
 * `mid_upper_arm_circumference_in_cm`, `body_temperature_in_f`, `fetal_heart_rate`,
 * `gestational_weight_gain`, `check_palm_and_nails`, `check_sclera_eyes`, `check_skin`,
 * `urine_test`.
 *
 * ### Cross-form fields — resolved separately, NOT by this object
 * `age`, `gravida`, `livingChildren`, `abortions`, `priorComplications` come from this
 * beneficiary's own `MOTHER_REGISTRATION` answers, not from [FormAnswers] passed in here — call
 * [AncRiskRegistrationResolver.addRegistrationFields] on this function's *result* to add them
 * (see that class's doc for exactly what it resolves and how). `pphHeavyBleedingFlag` remains
 * genuinely unresolved even there — blocked on a missing data source (confirmed by backend
 * 2026-08-24: no field exists on any form today, pending a 4th clinical decision alongside the
 * other three — see [AncRiskRegistrationResolver]'s doc). `fundalHeightDeviationCm` is different:
 * backend confirmed (2026-08-24) the *pack itself* will compute it from
 * [VisitFormQuestionCodes.FUNDAL_HEIGHT_CM]/[VisitFormQuestionCodes.CURRENT_GESTATIONAL_AGE_WEEKS]
 * once the rule-pack update ships — both are sent below (see that constant's own doc for why
 * sending them now, ahead of the pack update, is safe).
 *
 * [sickleCellValueCode] is intentionally accepted but NOT added to the returned object — the ANC
 * risk pack does not read a sickle-cell field at all yet (pending the clinical sign-off on
 * whether/how it should elevate Hb risk). Passed through so callers don't need to change their
 * call site again once that decision lands and the pack starts reading it; until then it's inert.
 */
object AncRiskAnswerMapper {

  fun toRuleInput(
    answers: FormAnswers,
    @Suppress("UNUSED_PARAMETER") sickleCellValueCode: String? = null,
  ): JsonObject = JsonObject().apply {
    numeric(answers, VisitFormQuestionCodes.BLOOD_PRESSURE_SYSTOLIC, "blood_pressure_bp_systolic")
    numeric(answers, VisitFormQuestionCodes.BLOOD_PRESSURE_DIASTOLIC, "blood_pressure_bp_diastolic")
    numeric(answers, VisitFormQuestionCodes.HAEMOGLOBIN, "haemoglobin_hb_g_dl")
    numeric(answers, VisitFormQuestionCodes.HEIGHT_CM, "height_of_the_woman_in_cm")
    numeric(answers, VisitFormQuestionCodes.BLOOD_GLUCOSE, "blood_glucose_in_mg_dl")
    numeric(answers, VisitFormQuestionCodes.MID_UPPER_ARM_CIRCUMFERENCE_CM, "mid_upper_arm_circumference_in_cm")
    numeric(answers, VisitFormQuestionCodes.BODY_TEMPERATURE_F, "body_temperature_in_f")
    numeric(answers, VisitFormQuestionCodes.FETAL_HEART_RATE, "fetal_heart_rate")
    numeric(answers, VisitFormQuestionCodes.FUNDAL_HEIGHT_CM, "fundal_height_in_cm")
    numeric(answers, VisitFormQuestionCodes.CURRENT_GESTATIONAL_AGE_WEEKS, "current_gestational_age_in_weeks")

    // gestational_weight_gain is a computed Normal/Severe string answer (see
    // VisitFormComputedFieldEvaluator), not numeric — pass its raw value straight through.
    answers.valueOf(VisitFormQuestionCodes.GESTATIONAL_WEIGHT_GAIN)?.takeIf { it.isNotBlank() }
      ?.let { addProperty("gestational_weight_gain", it) }

    answers.valueOf(VisitFormQuestionCodes.CHECK_PALM_AND_NAILS)?.let { addProperty("check_palm_and_nails", it) }
    answers.valueOf(VisitFormQuestionCodes.CHECK_SCLERA_EYES)?.let { addProperty("check_sclera_eyes", it) }
    answers.valueOf(VisitFormQuestionCodes.CHECK_SKIN)?.let { addProperty("check_skin", it) }
    answers.valueOf(VisitFormQuestionCodes.URINE_TEST)?.let { addProperty("urine_test", it) }

    // BMI: computed the same way VisitFormComputedFieldEvaluator.compute(COMPUTED_BMI, ...) does
    // (height + first-matching weight-field spelling) rather than calling that function directly,
    // since this mapper only needs the numeric value, not the formatted display string it returns.
    val heightCm = answers.valueOf(VisitFormQuestionCodes.HEIGHT_CM)?.toDoubleOrNull()
    val weightKg = VisitFormQuestionCodes.WEIGHT_KG_QUESTION_CODES
      .firstNotNullOfOrNull { code -> answers.valueOf(code)?.toDoubleOrNull() }
    if (heightCm != null && heightCm > 0.0 && weightKg != null) {
      val heightM = heightCm / 100.0
      addProperty("bmi", weightKg / (heightM * heightM))
    }

    val dangerSigns = answers.multiValueOf(VisitFormQuestionCodes.DANGER_SIGNS)
      .filter { it != VisitFormQuestionCodes.ValueCode.NO_ABNORMAL_SIGNS_AND_SYMPTOMS }
    if (dangerSigns.isNotEmpty() || answers.valueOf(VisitFormQuestionCodes.DANGER_SIGNS) != null) {
      add(
        "have_you_been_experiencing_any_of_these_since_the_last_visit",
        JsonArray().apply { dangerSigns.forEach { add(it) } },
      )
    }
  }

  private fun JsonObject.numeric(answers: FormAnswers, questionCode: String, packKey: String) {
    answers.valueOf(questionCode)?.toDoubleOrNull()?.let { addProperty(packKey, it) }
  }
}
