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

/** CR-020 Children Register: the `current_age_of_infant_in_days` field is declared
 * `computedFrom: "CHILD_AGE_MONTHS"`. NOTE the deliberate label/name mismatch — the field's label
 * says "in days" and the eligibility rules (0..365 direct, 0..183 registered-mother) are all in
 * DAYS, but the `computedFrom` token the backend chose is `CHILD_AGE_MONTHS`. We compute DAYS here
 * to match the field's label and the day-based eligibility windows; if the backend ever intends
 * MONTHS for this token, this must be revisited. Mother schema never emits this token, so the
 * mother flow is unaffected. */
private const val COMPUTED_CHILD_AGE_MONTHS = "CHILD_AGE_MONTHS"

/** Infant DOB question the child-age value derives from (CR-020). */
private const val INFANT_DOB_QUESTION_CODE = "date_of_birth_of_infant"

/** DOB question the `age_years` value derives from. Live schema v9 (2026-07-22) renamed this from
 * the old `age_of_the_beneficiary` to `date_of_birth` — keep in sync with
 * `DynamicFormSubmissionMapper`'s `QuestionCode.DATE_OF_BIRTH`. */
const val DOB_QUESTION_CODE = "date_of_birth"

/** The DOB-derived age question. The backend declares it as a plain `number` field and — unlike
 * EDD/gestational age — never marks it `computedFrom: "AGE_FROM_DOB"`, so it would never auto-fill
 * on its own. It has also been renamed more than once across schema versions
 * (`age_of_the_beneficiary` on the live `api.armman.org` schema, `age_years` on the earlier v9),
 * so we match ANY of these known codes rather than a single one — resilient to the next rename
 * instead of silently breaking on it. Whichever code the live schema uses is auto-filled by
 * [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.recomputeDerivedFields] and shown
 * read-only by [org.armman.sakhi.ui.forms.DynamicFormRenderer]. The formula (whole years between
 * DOB and registration date) is standard and unambiguous — unlike `UNIQUE_ID`, it needs no backend
 * confirmation. Remove this stopgap once the backend declares `computedFrom` on the field. */
val AGE_FROM_DOB_QUESTION_CODES: Set<String> = setOf("age_of_the_beneficiary", "age_years")

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
 * `AGE_FROM_DOB` is not currently declared by the live schema (see [AGE_FROM_DOB_QUESTION_CODES]'
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

      // CR-020: age in DAYS between the infant DOB and the registration date (see
      // COMPUTED_CHILD_AGE_MONTHS's doc for the label/token "months" vs computed "days" mismatch).
      COMPUTED_CHILD_AGE_MONTHS -> infantDob(answers)?.let {
        ChronoUnit.DAYS.between(it, registrationDate).toString()
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

  private fun infantDob(answers: FormAnswers): LocalDate? =
    answers.valueOf(INFANT_DOB_QUESTION_CODE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
