package org.armman.sakhi.data.visitform

import org.armman.sakhi.data.forms.FormAnswers
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** `computedFrom` tokens the live INFANT_VISIT schema declares (v2, 2026-08-07). */
private const val COMPUTED_AGE_IN_MONTHS = "CHILD_AGE_MONTHS"
private const val COMPUTED_NUTRITIONAL_ZSCORE = "NUTRITIONAL_ZSCORE"

/** INFANT_VISIT's own DOB question code — distinct from
 * [org.armman.sakhi.data.forms.ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT], the
 * CHILD_REGISTRATION form's code for the same fact. */
private const val DOB_QUESTION_CODE = "date_of_birth"

/**
 * Evaluates INFANT_VISIT's `computedFrom` fields. A separate object from
 * [VisitFormComputedFieldEvaluator] (the ANC_VISIT/mother evaluator) and from the shared
 * [org.armman.sakhi.data.forms.FormComputedFieldEvaluator] — that shared evaluator already declares
 * a `CHILD_AGE_MONTHS` token for CHILD_REGISTRATION's `current_age_of_infant_in_days` field, but
 * (per that constant's own doc) deliberately computes DAYS to match THAT field's label and
 * day-based eligibility windows. INFANT_VISIT's `age_in_months` field needs actual MONTHS — it
 * gates `muac_in_cms` (>= 6 months) and `thermal_care_practices` (< 2 months) and is labelled "Age
 * in months" — so reusing the shared evaluator's DAYS formula under the same token name would
 * silently return the wrong unit here. Keeping this as its own object avoids that collision without
 * touching the shared evaluator's registration-form behaviour.
 */
object InfantVisitFormComputedFieldEvaluator {

  fun compute(computedFrom: String, answers: FormAnswers, visitDate: LocalDate): String? =
    when (computedFrom) {
      COMPUTED_AGE_IN_MONTHS -> dob(answers)?.let {
        ChronoUnit.MONTHS.between(it, visitDate).toString()
      }

      // Nutritional status (wasting/stunting/underweight) needs WHO growth-standard z-score
      // tables to compute from age/weight/length — not available client-side and not something to
      // guess at, same "leave unimplemented until confirmed" precedent as
      // org.armman.sakhi.data.forms.FormComputedFieldEvaluator's COMPUTED_UNIQUE_ID. Renders
      // read-only/"Auto-calculated"; does not block the Sakhi.
      COMPUTED_NUTRITIONAL_ZSCORE -> null

      else -> null
    }

  private fun dob(answers: FormAnswers): LocalDate? =
    answers.valueOf(DOB_QUESTION_CODE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
