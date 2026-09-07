package org.armman.sakhi.data.visitform

import org.armman.sakhi.data.forms.FormAnswers
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** `computedFrom` tokens the live ANC_VISIT schema declares (v1, 2026-08-07). Deliberately a
 * separate set of constants/evaluator from
 * [org.armman.sakhi.data.forms.FormComputedFieldEvaluator] rather than adding branches to it: that
 * evaluator's `EDD_FROM_LMP`/`GESTATIONAL_AGE_AT_REGISTRATION` are hardcoded to the
 * MOTHER_REGISTRATION schema's `lmp_date` question code, and ANC_VISIT uses a different question
 * code (`lmp`/`lmp_date_edit`) and different token names (`GESTATIONAL_AGE_AT_VISIT`, not
 * `GESTATIONAL_AGE_AT_REGISTRATION`) — reusing the shared evaluator would mean either duplicating
 * its LMP lookup with a different key (silently wrong for the schema it already serves) or risking
 * a regression in the working Mother Registration flow to make room for a second LMP source. */
private const val COMPUTED_EDD_FROM_LMP = "EDD_FROM_LMP"
private const val COMPUTED_GESTATIONAL_AGE_AT_VISIT = "GESTATIONAL_AGE_AT_VISIT"
private const val COMPUTED_BMI = "BMI"

/** `gestational_weight_gain`, radio Normal/Severe (spec: 10-12 kg total gain, at 0.2-0.3 kg/week
 * in the 2nd/3rd trimester). Implemented in [gestationalWeightGain] once a registration-time
 * baseline weight became available via [VisitContext.registrationWeightKg] (bharath, 2026-08-08 —
 * confirmed with the user; previously left unimplemented, same reasoning as
 * [org.armman.sakhi.data.forms.FormComputedFieldEvaluator]'s `COMPUTED_UNIQUE_ID`, because no
 * baseline existed yet). */
private const val COMPUTED_GESTATIONAL_WEIGHT_GAIN = "GESTATIONAL_WEIGHT_GAIN"

private const val EDD_OFFSET_DAYS = 280L
private const val DAYS_PER_WEEK = 7L

/** The spec's rate (0.2-0.3 kg/week) only applies from the 2nd trimester on — before that there's
 * no sound basis to call a shortfall "Severe", so [gestationalWeightGain] returns null (renders
 * "Auto-calculated") rather than guess one for an earlier gestational age. */
private const val SECOND_TRIMESTER_START_WEEK = 13L
private const val WEEKLY_GAIN_MIN_KG = 0.2

/** Tolerance for the weight-gain floor comparison. Both sides are accumulated in binary floating
 * point (a 55.4-54.0 subtraction lands on 1.3999999999999986 while 7 * 0.2 lands on
 * 1.4000000000000001), so a gain that exactly meets the target compared false and flagged a
 * healthy mother [VALUE_SEVERE] -> a spurious referral. 1 gram is far below the precision of any
 * field weighing scale, so it cannot mask a real shortfall. */
private const val WEIGHT_GAIN_TOLERANCE_KG = 0.001

private const val VALUE_NORMAL = "Normal"
private const val VALUE_SEVERE = "Severe"

/** Evaluates ANC_VISIT's [org.armman.sakhi.data.forms.FormFieldSchema.computedFrom] fields against
 * the in-progress visit [FormAnswers] and the visit date (the visit-form analogue of
 * [org.armman.sakhi.data.forms.FormComputedFieldEvaluator]'s `registrationDate` parameter). */
object VisitFormComputedFieldEvaluator {

  fun compute(
    computedFrom: String,
    answers: FormAnswers,
    visitDate: LocalDate,
    registrationWeightKg: Double? = null,
  ): String? =
    when (computedFrom) {
      COMPUTED_EDD_FROM_LMP -> lmpDate(answers)?.plusDays(EDD_OFFSET_DAYS)?.toString()

      COMPUTED_GESTATIONAL_AGE_AT_VISIT -> lmpDate(answers)?.let {
        (ChronoUnit.DAYS.between(it, visitDate) / DAYS_PER_WEEK).toString()
      }

      COMPUTED_BMI -> bmi(answers)

      COMPUTED_GESTATIONAL_WEIGHT_GAIN -> gestationalWeightGain(answers, visitDate, registrationWeightKg)

      else -> null
   }

  /**
   * The sonography-confirmed [org.armman.sakhi.data.visitform.VisitFormQuestionCodes.LMP_DATE_EDIT]
   * takes priority over the Sakhi's initial
   * [org.armman.sakhi.data.visitform.VisitFormQuestionCodes.LMP] entry once it's answered — it only
   * renders/becomes answerable after `do_you_have_a_sonography_report_to_confirm_the_lmp_date` is
   * "yes", at which point it is the more accurate of the two per the form's own design. This
   * priority (rather than, say, always preferring the newest edited field) is a reasonable default
   * given the schema's visibleWhen gating, not a confirmed product rule — flagged for ARMMAN
   * sign-off alongside the rest of this evaluator.
   */
  private fun lmpDate(answers: FormAnswers): LocalDate? {
    val edited = answers.valueOf(VisitFormQuestionCodes.LMP_DATE_EDIT)
    val raw = answers.valueOf(VisitFormQuestionCodes.LMP)
    return (edited ?: raw)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
  }

  private fun bmi(answers: FormAnswers): String? {
    val heightCm = answers.valueOf(VisitFormQuestionCodes.HEIGHT_CM)?.toDoubleOrNull() ?: return null
    // Bug fix (2026-08-21): looks up every known weight-field spelling
    // (VisitFormQuestionCodes.WEIGHT_KG_QUESTION_CODES) rather than the single ANC-only
    // WEIGHT_KG constant, so this also finds POSTPARTUM_VISIT's own `current_weight_kg` answer —
    // see that set's doc comment for why PP1 needed a second spelling here.
    val weightKg = VisitFormQuestionCodes.WEIGHT_KG_QUESTION_CODES
      .firstNotNullOfOrNull { code -> answers.valueOf(code)?.toDoubleOrNull() } ?: return null
    if (heightCm <= 0.0) return null
    val heightM = heightCm / 100.0
    val bmiValue = weightKg / (heightM * heightM)
    return String.format("%.1f", bmiValue)
  }

  /**
   * Total gestational weight gain (this visit's [VisitFormQuestionCodes.WEIGHT_KG] minus
   * [registrationWeightKg]) against the trimester-prorated target — see [SECOND_TRIMESTER_START_WEEK]
   * /[WEEKLY_GAIN_MIN_KG]'s docs for why this returns null before the 2nd trimester or without a
   * baseline. Only a shortfall is flagged [VALUE_SEVERE] — the spec's risk condition is
   * specifically "weight gain below recommended range" (-> Referral + Health message); gaining
   * faster than the top of the 0.2-0.3 kg/week range is left [VALUE_NORMAL] here since no risk
   * condition names it (bharath, 2026-08-08, confirmed with the user — flag for ARMMAN sign-off if
   * excess gain should also be flagged later).
   */
  private fun gestationalWeightGain(
    answers: FormAnswers,
    visitDate: LocalDate,
    registrationWeightKg: Double?,
  ): String? {
    val currentWeightKg = answers.valueOf(VisitFormQuestionCodes.WEIGHT_KG)?.toDoubleOrNull() ?: return null
    val baselineWeightKg = registrationWeightKg ?: return null
    val lmp = lmpDate(answers) ?: return null

    val gestationalWeeks = ChronoUnit.DAYS.between(lmp, visitDate) / DAYS_PER_WEEK
    if (gestationalWeeks < SECOND_TRIMESTER_START_WEEK) return null

    val weeksInRange = (gestationalWeeks - SECOND_TRIMESTER_START_WEEK).toDouble()
    val expectedMinGainKg = weeksInRange * WEEKLY_GAIN_MIN_KG
    val actualGainKg = currentWeightKg - baselineWeightKg

    return if (actualGainKg < expectedMinGainKg - WEIGHT_GAIN_TOLERANCE_KG) VALUE_SEVERE else VALUE_NORMAL
  }

  /**
   * Whether [org.armman.sakhi.data.visitform.VisitFormQuestionCodes.LMP_DATE_EDIT] should render
   * read-only (locked) even though the schema's own `visibleWhen` already shows it.
   *
   * The live schema renders [org.armman.sakhi.data.visitform.VisitFormQuestionCodes.LMP_DATE_EDIT]
   * and [org.armman.sakhi.data.visitform.VisitFormQuestionCodes.UPLOAD_SONOGRAPHY_REPORT_IMAGE] on
   * the same `visibleWhen` condition (sonography = Yes), both mandatory, with no ordering between
   * them enforced by the schema itself. Product/meeting-notes intent (2026-03-24) is that the
   * corrected LMP date should only be enterable once the sonography photo backing it exists — a
   * Sakhi shouldn't be able to type a date with no evidence yet to support it. This is a
   * host-screen lock layered on top of the schema's visibility (via
   * [org.armman.sakhi.ui.forms.DynamicFormField]'s `readOnlyQuestionCodes`), not a change to
   * [org.armman.sakhi.data.forms.FormVisibilityEvaluator] itself — that evaluator is deliberately
   * mirrored against the backend's own `isVisible` so app-hidden and backend-excluded stay in sync;
   * this lock is purely an app-side UX guard on a field the backend already considers visible.
   *
   * Locked (true) whenever [org.armman.sakhi.data.visitform.VisitFormQuestionCodes
   * .UPLOAD_SONOGRAPHY_REPORT_IMAGE] has no answer yet. Callers are expected to only consult this
   * for a field already known to be visible (i.e. sonography = Yes) — this function doesn't
   * re-check that condition itself, since [org.armman.sakhi.ui.visitform.DynamicVisitFormScreen]
   * only iterates fields [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.visibleFields]
   * already filtered to visible.
   */
  fun isLmpDateEditLocked(answers: FormAnswers): Boolean =
    answers.valueOf(VisitFormQuestionCodes.UPLOAD_SONOGRAPHY_REPORT_IMAGE).isNullOrBlank()
}
