package org.armman.sakhi.data.forms

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** LMP question the EDD/gestational-age values derive from, and the subject of
 * [FormDateRuleset]'s LMP window rule. */
const val LMP_DATE_QUESTION_CODE = "lmp_date"

/**
 * Registration date, TYPO spelling — `registrtion_date`, missing the second `a`. Kept verbatim
 * because it is the live `question_code` on CHILD_REGISTRATION v2. Also the code written when the
 * active schema declares no registration-date field at all, so the answer still reaches the
 * `formData` blob.
 */
const val REGISTRATION_DATE_QUESTION_CODE = "registrtion_date"

/** Registration date, CORRECTED spelling — published on MOTHER_REGISTRATION v3 (2026-07-31). */
const val REGISTRATION_DATE_QUESTION_CODE_CORRECTED = "registration_date"

/**
 * Every `question_code` a published schema has used for the registration date. ARMMAN publishes the
 * two spellings above on different forms (typo on CHILD_REGISTRATION v2, corrected on
 * MOTHER_REGISTRATION v3), and either may appear on either form after the next publish, so every
 * rule keyed on this field matches the SET rather than one string. Getting this wrong is silent —
 * the field still renders, but the prefill, the "not in the future" bound and the LMP/DOB reference
 * date all no-op.
 */
val REGISTRATION_DATE_QUESTION_CODES: Set<String> =
  setOf(REGISTRATION_DATE_QUESTION_CODE, REGISTRATION_DATE_QUESTION_CODE_CORRECTED)

/** The answered registration date under whichever spelling the active schema uses, or null when
 * unanswered under both. */
fun FormAnswers.registrationDateAnswer(): String? =
  REGISTRATION_DATE_QUESTION_CODES.firstNotNullOfOrNull { code ->
    valueOf(code)?.takeIf { it.isNotBlank() }
  }

private const val EDD_OFFSET_DAYS = 280L
private const val DAYS_PER_WEEK = 7L

private const val COMPUTED_EDD_FROM_LMP = "EDD_FROM_LMP"
private const val COMPUTED_GESTATIONAL_AGE_AT_REGISTRATION = "GESTATIONAL_AGE_AT_REGISTRATION"
private const val COMPUTED_UNIQUE_ID = "UNIQUE_ID"
const val COMPUTED_AGE_FROM_DOB = "AGE_FROM_DOB"

/** Internal-only token for the trimester stopgap — the backend declares no `computedFrom` for
 * [TRIMESTER_QUESTION_CODE], so nothing publishes this string; it only has to match between
 * [FormComputedFieldEvaluator.compute] and the ViewModel stopgap that calls it. */
const val COMPUTED_TRIMESTER = "TRIMESTER_OF_PREGNANCY"

/** CR-020 Children Register: the `current_age_of_infant_in_days` field is declared
 * `computedFrom: "CHILD_AGE_MONTHS"`. NOTE the deliberate label/name mismatch — the field's label
 * says "in days" and the eligibility rules (0..365 direct, 0..183 registered-mother) are all in
 * DAYS, but the `computedFrom` token the backend chose is `CHILD_AGE_MONTHS`. We compute DAYS here
 * to match the field's label and the day-based eligibility windows; if the backend ever intends
 * MONTHS for this token, this must be revisited. Mother schema never emits this token, so the
 * mother flow is unaffected. */
private const val COMPUTED_CHILD_AGE_MONTHS = "CHILD_AGE_MONTHS"

/** Infant DOB question the child-age value derives from (CR-020). Aliases the shared declaration so
 * this file does not hold a second copy of the literal. */
private const val INFANT_DOB_QUESTION_CODE = ChildRegistrationQuestionCodes.DATE_OF_BIRTH_OF_INFANT

/** DOB question the `age_years` value derives from. Live schema v9 (2026-07-22) renamed this from
 * the old `age_of_the_beneficiary` to `date_of_birth` — keep in sync with
 * `DynamicFormSubmissionMapper`'s `QuestionCode.DATE_OF_BIRTH`. */
const val DOB_QUESTION_CODE = "date_of_birth"

/**
 * Spec row 35 ("Trimester of preganancy" — the sheet's own typo, carried into the live
 * `question_code`). Declared by the live schema as a plain `input_type: "text"` field with no
 * `computedFrom` — nothing auto-fills it, so the Sakhi types straight into it. Production data
 * (`api-calls.jsonl`/`api-calls-live.jsonl`) shows exactly the result: raw fat-fingered digits
 * ("666", "55665", "56") instead of the 1st/2nd/3rd the spec's formula would produce. Computed here
 * as a stopgap the same way [AGE_FROM_DOB_QUESTION_CODES] is — see that constant's doc for why this
 * is safe to do client-side without backend confirmation: the formula
 * (`Floor((RegDate-LMP)/7)` then bucketed into trimesters) is exactly [COMPUTED_GESTATIONAL_AGE_AT_REGISTRATION]'s
 * own formula, just bucketed, so it needs no new business input. Remove this stopgap once the
 * backend declares `computedFrom` on the field.
 */
const val TRIMESTER_QUESTION_CODE = "trimester_of_preganancy"

/**
 * The MOTHER's DOB, collected on the CHILD_REGISTRATION form (Infant Registration spec row 20.0:
 * "Age or DOB of the mother"). Distinct from [DOB_QUESTION_CODE], which is the beneficiary's own DOB
 * on the mother-enrollment form — on the child form the beneficiary is the infant
 * ([INFANT_DOB_QUESTION_CODE]), so the mother's DOB needs its own code.
 *
 * The spec's 10–50 year range is identical to the mother form's row 23, so [FormDateRuleset] applies
 * the SAME rule to both codes rather than duplicating it.
 *
 * Per CR-039 (2026-08-06), `CHILD_REGISTRATION` now declares this as its own plain, independently
 * editable `date` field, split out of the old single `age_or_dob_of_the_mother` field (which had
 * shipped with an unrenderable `input_type: "date,integer"`). The mother's age lives in a sibling
 * `number` field, `mother_age` — plain, `numericRange 10..50`, no `computedFrom` — and the spec's
 * "either DOB or age" requirement is enforced entirely by a schema `ANY_OF_REQUIRED` rule over
 * `["mother_date_of_birth", "mother_age"]` (handled generically by [FormCrossFieldValidator], same
 * mechanism as CR-037's mother-form pair — no per-field code needed for that part). Unlike
 * [AGE_FROM_DOB_QUESTION_CODES], `mother_age` is never derived/read-only: both fields stay
 * independently Sakhi-editable at all times. See
 * `docs/backend-requests/CR-039-child-mother-age-dob-field-regression.md`.
 *
 * The value is the code the PUBLISHED schema uses, verified against the live `active-version`
 * response (PR #119, `fix/mother-registration-form-schema`, 2026-08-06) — not
 * `age_or_dob_of_the_mother`, the earlier (now retired) single-field code this replaced. Getting
 * this string wrong is silent: the field still renders, but every rule keyed on it (date bounds,
 * the 10–50 gate, the mother prefill) simply never matches, which is exactly the defect the earlier
 * rename fixed.
 */
const val MOTHER_DOB_QUESTION_CODE = "mother_date_of_birth"

/**
 * The DOB-derived age question. Per CR-037 (2026-08-06), `MOTHER_REGISTRATION` now declares
 * `date_of_birth` and `age_of_the_beneficiary` as two separate fields, `age_of_the_beneficiary`
 * carrying `computedFrom: "AGE_FROM_DOB"` and a schema `ANY_OF_REQUIRED` rule over the pair (spec
 * row 20: "Either date of birth or age should be filled") — see
 * `docs/backend-requests/CR-037-beneficiary-age-dob-field-gap.md`. This set still matches by
 * `question_code` rather than by `computedFrom` alone, and still includes the older `age_years`
 * alias, for the same resilience-to-rename reason as before.
 *
 * Unlike every other `computedFrom` field, this one has a legitimate manual fallback: when
 * `date_of_birth` is blank the Sakhi types the age directly, per the spec's "either" wording. So,
 * unlike EDD/gestational age/unique_id, this field is read-only ONLY while `date_of_birth` has a
 * value — see [isAgeFromDobReadOnly] — and both
 * [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.recomputeDerivedFields] and
 * [org.armman.sakhi.ui.forms.DynamicFormRenderer] must consult that, not just `computedFrom !=
 * null`, or a manually-typed age gets silently clobbered/never becomes editable. */
val AGE_FROM_DOB_QUESTION_CODES: Set<String> = setOf("age_of_the_beneficiary", "age_years")

/** True while an [AGE_FROM_DOB_QUESTION_CODES] field should render read-only and be
 * backend/derivation-owned (DOB is answered, so the value is [COMPUTED_AGE_FROM_DOB]-derived);
 * false once [DOB_QUESTION_CODE] is blank, meaning the spec's "either DOB or age" fallback applies
 * and the field must become a normal Sakhi-editable number input instead. */
fun isAgeFromDobReadOnly(answers: FormAnswers): Boolean =
  !answers.valueOf(DOB_QUESTION_CODE).isNullOrBlank()

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
 * `AGE_FROM_DOB` is declared by the live `MOTHER_REGISTRATION` schema as of CR-037 (see
 * [AGE_FROM_DOB_QUESTION_CODES]' doc). The ViewModel's stopgap direct-invocation path (for a
 * schema version that hasn't picked up the declaration yet) still exists as a fallback.
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

      // Spec row 35: gestational age in weeks, bucketed into trimester 1/2/3. Same LMP/registration
      // formula as COMPUTED_GESTATIONAL_AGE_AT_REGISTRATION, computed independently here rather than
      // read back off that field's answer so this doesn't depend on schema field ordering.
      COMPUTED_TRIMESTER -> lmpDate(answers)?.let {
        val gestationalAgeWeeks = ChronoUnit.DAYS.between(it, registrationDate) / DAYS_PER_WEEK
        trimesterFor(gestationalAgeWeeks).toString()
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

  /** Spec row 35's thresholds: <14w = 1st trimester, 14–27w = 2nd, >=28w = 3rd. */
  private const val SECOND_TRIMESTER_WEEK = 14
  private const val THIRD_TRIMESTER_WEEK = 28

  private fun trimesterFor(gestationalAgeWeeks: Long): Int = when {
    gestationalAgeWeeks < SECOND_TRIMESTER_WEEK -> 1
    gestationalAgeWeeks < THIRD_TRIMESTER_WEEK -> 2
    else -> 3
  }

  private fun infantDob(answers: FormAnswers): LocalDate? =
    answers.valueOf(INFANT_DOB_QUESTION_CODE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
