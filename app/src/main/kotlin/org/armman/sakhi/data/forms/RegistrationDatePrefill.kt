package org.armman.sakhi.data.forms

import java.time.LocalDate

/**
 * Auto-fills the registration date with today, per form spec row 13 ("Automatically popup todays
 * date") — for BOTH the mother (`MOTHER_REGISTRATION`) and child (`CHILD_REGISTRATION`) flows.
 *
 * Shared on purpose. The child registration data layer is otherwise a clone of the mother's, and the
 * defect this fixes was exactly that drift: the mother ViewModel had its own private prefill keyed on
 * the typo spelling only, so (a) the child form never prefilled at all and (b) the mother form
 * stopped prefilling the moment ARMMAN published v3 with the corrected `registration_date` spelling.
 * One implementation, matching every code in [REGISTRATION_DATE_QUESTION_CODES], removes both
 * failure modes.
 *
 * Behaviour:
 * - Skips when the date is already answered under EITHER spelling, so a resumed draft keeps the date
 *   it was started on instead of silently jumping to today.
 * - Writes every registration-date code the ACTIVE schema declares — normally exactly one. Writing
 *   the declared code (rather than a hardcoded one) is what makes the answer survive the
 *   `/submissions` schema validation, which 422s on an absent required field.
 * - When the schema declares NO registration-date field, falls back to
 *   [REGISTRATION_DATE_QUESTION_CODE] so the answer is still present in the `formData` blob and both
 *   submission mappers pick it up instead of their own fallback date. Nothing renders in that case.
 *
 * The written value is ISO `yyyy-MM-dd` ([LocalDate.toString]) — the format `DynamicFormRenderer`
 * parses back for the date picker and the backend expects for `case.registrationDate`.
 */
object RegistrationDatePrefill {

  /**
   * [answers] with the registration date set to [registrationDate] (today, from the caller's
   * ViewModel), or unchanged when it is already answered.
   *
   * @param fields the active version's `schemaJson`.
   */
  fun apply(
    fields: List<FormFieldSchema>,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): FormAnswers {
    if (answers.registrationDateAnswer() != null) return answers
    val value = registrationDate.toString()
    return targetCodes(fields).fold(answers) { acc, code -> acc.withSingleValue(code, value) }
  }

  private fun targetCodes(fields: List<FormFieldSchema>): List<String> = fields
    .map { it.questionCode }
    .filter { it in REGISTRATION_DATE_QUESTION_CODES }
    .distinct()
    .ifEmpty { listOf(REGISTRATION_DATE_QUESTION_CODE) }
}
