package org.armman.sakhi.data.forms

import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val LMP_DATE_QUESTION_CODE = "lmp_date"
private const val EDD_OFFSET_DAYS = 280L
private const val DAYS_PER_WEEK = 7L

private const val COMPUTED_EDD_FROM_LMP = "EDD_FROM_LMP"
private const val COMPUTED_GESTATIONAL_AGE_AT_REGISTRATION = "GESTATIONAL_AGE_AT_REGISTRATION"
private const val COMPUTED_UNIQUE_ID = "UNIQUE_ID"
const val COMPUTED_AGE_FROM_DOB = "AGE_FROM_DOB"

/** DOB question the `age_years` value derives from. Live schema v9 (2026-07-22) renamed this from
 * the old `age_of_the_beneficiary` to `date_of_birth` — keep in sync with
 * `DynamicFormSubmissionMapper`'s `QuestionCode.DATE_OF_BIRTH`. */
const val DOB_QUESTION_CODE = "date_of_birth"

/** The live v5 schema declares a separate `age_years` number question (label "Age (years)") but,
 * unlike EDD/gestational age, does NOT mark it `computedFrom: "AGE_FROM_DOB"` — so it never
 * auto-fills from the DOB field. Unlike `UNIQUE_ID`, this formula (whole years between DOB and
 * registration date) is standard and unambiguous, not a business rule needing backend
 * confirmation, so [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel] special-cases
 * this question code as a stopgap until the backend adds the `computedFrom` declaration — see that
 * class's `recomputeDerivedFields` and [org.armman.sakhi.ui.forms.DynamicFormRenderer]'s matching
 * read-only treatment for this code. */
const val AGE_YEARS_QUESTION_CODE = "age_years"

/**
 * Evaluates [FormFieldSchema.computedFrom] fields — values the Sakhi never types, derived from
 * other answers. Formulas for `EDD_FROM_LMP`/`GESTATIONAL_AGE_AT_REGISTRATION` are the exact same
 * ones the existing static Personal Info step already uses (`PersonalInfoState.edd`/
 * `.gestationalAgeWeeks`), just re-hosted here so the dynamic renderer can call them generically.
 *
 * `UNIQUE_ID` has no known formula anywhere in the backend codebase as of CR-018 — per product
 * decision, this is deliberately left unimplemented (returns null) until confirmed. Do not guess
 * at a formula here; a wrong one would ship an incorrect identifier silently.
 *
 * `AGE_FROM_DOB` is not currently declared by the live schema (see [AGE_YEARS_QUESTION_CODE]'s
 * doc) but is implemented here anyway — ready for the day the backend adds the declaration — and
 * also invoked directly as a stopgap by the ViewModel in the meantime.
 */
object FormComputedFieldEvaluator {

  fun compute(computedFrom: String, answers: FormAnswers, registrationDate: LocalDate): String? =
    when (computedFrom) {
      COMPUTED_EDD_FROM_LMP -> lmpDate(answers)?.plusDays(EDD_OFFSET_DAYS)?.toString()

      COMPUTED_GESTATIONAL_AGE_AT_REGISTRATION -> lmpDate(answers)?.let {
        (ChronoUnit.DAYS.between(it, registrationDate) / DAYS_PER_WEEK).toString()
      }

      COMPUTED_AGE_FROM_DOB -> dob(answers)?.let {
        ChronoUnit.YEARS.between(it, registrationDate).toString()
      }

      // TODO(CR-018): formula not yet confirmed with backend — user will follow up and we'll
      // configure this once known. Do not implement a guessed formula in the meantime.
      COMPUTED_UNIQUE_ID -> null

      else -> null
    }

  private fun lmpDate(answers: FormAnswers): LocalDate? =
    answers.valueOf(LMP_DATE_QUESTION_CODE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

  private fun dob(answers: FormAnswers): LocalDate? =
    answers.valueOf(DOB_QUESTION_CODE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
