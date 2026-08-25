package org.armman.sakhi.data.visitform

import com.google.gson.JsonObject
import org.armman.sakhi.data.beneficiary.LocalEnrolmentBeneficiarySource
import org.armman.sakhi.data.forms.DOB_QUESTION_CODE
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormObstetricRuleset
import org.armman.sakhi.data.forms.MotherRegistrationQuestionCodes
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Closes part of the ANC-risk cross-form gap [AncRiskAnswerMapper]'s doc flags: resolves
 * `age`/`gravida`/`livingChildren`/`abortions`/`priorComplications` from this beneficiary's own
 * cached `MOTHER_REGISTRATION` answers, on-device, no network call — the client-side counterpart
 * to what backend's `resolveAncRiskRegistrationAnswers` does server-side (that function itself is
 * not portable; this is a fresh implementation reading the same underlying data this app already
 * has locally).
 *
 * Built on [LocalEnrolmentBeneficiarySource.answersFor], the same offline lookup
 * [org.armman.sakhi.ui.delivery.DeliveryChildRegistrationViewModel] already relies on to read a
 * mother's registration answers back for a locally enrolled beneficiary — not a new access path.
 *
 * ### Still NOT resolved here — genuinely blocked, not a coding gap
 * - `pphHeavyBleedingFlag`: the live `MOTHER_REGISTRATION` schema's own prior-delivery
 *   complications question ([MotherRegistrationQuestionCodes.PREVIOUS_DELIVERY_COMPLICATIONS])
 *   has no PPH-specific `value_code` — only generic "complications during delivery"/"complications
 *   with baby" options. The only place a `"postpartum_hemorrhage_pph"` value_code exists in the
 *   confirmed 2026-08-24 schema dump is `DELIVERY_VISIT`'s own complications field, which records
 *   THIS pregnancy's outcome (not yet happened, at ANC-visit time) — the wrong source. No
 *   registration-time field distinguishes "her prior delivery specifically had heavy PPH
 *   bleeding" from the other complication types. Backend independently confirmed the same finding
 *   2026-08-24 (no field anywhere in the codebase maps to this — not just this resolver's own
 *   registration-answers slice) and folded it in as a 4th clinical decision alongside the other
 *   three open ones; still needs that sign-off before this can be filled in.
 *
 * `fundalHeightDeviationCm` is NOT on this list anymore — backend confirmed 2026-08-24 the ANC
 * risk pack itself will compute it (`fundal_height_in_cm - current_gestational_age_in_weeks`)
 * once its rule-pack update ships, reading the two raw fields directly rather than expecting a
 * pre-computed deviation. Both raw fields are sent by [AncRiskAnswerMapper] already (see
 * [VisitFormQuestionCodes.FUNDAL_HEIGHT_CM]'s doc) — nothing for this resolver to add.
 */
@Singleton
class AncRiskRegistrationResolver @Inject constructor(
  private val localEnrolmentBeneficiarySource: LocalEnrolmentBeneficiarySource,
) {

  /**
   * Adds `age`, `gravida`, `livingChildren`, `abortions`, `priorComplications` to [target] from
   * [motherLocalBeneficiaryId]'s cached registration answers, if available. A field already
   * present in [target] (shouldn't normally happen — these keys are cross-form-only, never part
   * of [AncRiskAnswerMapper]'s own output) is left untouched rather than overwritten. No-op,
   * leaving [target] as-is, if registration answers aren't available locally (never enrolled on
   * this device, or a lookup miss) — same "degrade gracefully, never block/crash" contract as
   * every other offline-first repository read in this app.
   */
  suspend fun addRegistrationFields(
    target: JsonObject,
    motherLocalBeneficiaryId: String,
    referenceDate: LocalDate = LocalDate.now(),
  ): JsonObject {
    val registrationAnswers = localEnrolmentBeneficiarySource.answersFor(motherLocalBeneficiaryId)
      ?: return target

    ageAtReference(registrationAnswers, referenceDate)?.let {
      if (!target.has("age")) target.addProperty("age", it)
    }
    intAnswer(registrationAnswers, FormObstetricRuleset.GRAVIDA)?.let {
      if (!target.has("gravida")) target.addProperty("gravida", it)
    }
    intAnswer(registrationAnswers, FormObstetricRuleset.LIVING_CHILDREN)?.let {
      if (!target.has("livingChildren")) target.addProperty("livingChildren", it)
    }
    intAnswer(registrationAnswers, FormObstetricRuleset.ABORTIONS)?.let {
      if (!target.has("abortions")) target.addProperty("abortions", it)
    }
    if (!target.has("priorComplications")) {
      target.addProperty("priorComplications", hasPriorComplications(registrationAnswers))
    }
    return target
  }

  /** Whole years between DOB and [referenceDate] — same computation as
   * [org.armman.sakhi.data.enrollment.EnrollmentRiskAssessment.ageAtRegistration], generalized to
   * any reference date (that function is pinned to the registration date; this pack input is
   * meant to reflect the beneficiary's age as of the CURRENT visit being graded, not registration,
   * so a visit years after enrolment reports her true current age). */
  private fun ageAtReference(answers: FormAnswers, referenceDate: LocalDate): Int? {
    val dob = parseDate(answers.valueOf(DOB_QUESTION_CODE)) ?: return null
    if (dob.isAfter(referenceDate)) return null
    return ChronoUnit.YEARS.between(dob, referenceDate).toInt()
  }

  /** True if she reported any real complication in a previous delivery — Q52's `no_complications`
   * is the only benign answer, same "any non-none selection carries risk" rule
   * [org.armman.sakhi.data.enrollment.EnrollmentRiskAssessment]'s `healthMessageFindings` already
   * applies to this exact field. Does not distinguish WHICH complication (see this class's doc on
   * why [pphHeavyBleedingFlag] can't be derived from this same question). */
  private fun hasPriorComplications(answers: FormAnswers): Boolean =
    answers.multiValueOf(MotherRegistrationQuestionCodes.PREVIOUS_DELIVERY_COMPLICATIONS)
      .any { it != MotherRegistrationQuestionCodes.ValueCode.NO_COMPLICATIONS }

  private fun intAnswer(answers: FormAnswers, questionCode: String): Int? =
    answers.valueOf(questionCode)?.takeIf { it.isNotBlank() }?.toIntOrNull()

  private fun parseDate(value: String?): LocalDate? {
    val raw = value?.takeIf { it.isNotBlank() } ?: return null
    return try {
      LocalDate.parse(raw)
    } catch (_: DateTimeParseException) {
      null
    }
  }
}
