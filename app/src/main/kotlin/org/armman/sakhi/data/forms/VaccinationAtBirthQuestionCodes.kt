package org.armman.sakhi.data.forms

/**
 * Q49 ("Vaccination taken at birth?") question/value codes, `CHILD_REGISTRATION`.
 *
 * Backend request `CR-040-vaccination-at-birth-dates-schema-gap.md` (raised 2026-08-07) asked
 * ARMMAN to add 4 real date fields since the published schema had nowhere to store a
 * per-vaccine date — `vaccination_taken_at_birth` is `input_type: "multiselect_date"`, which the
 * app (deliberately, see [FormVisibilityEvaluator]'s KDoc) renders as a plain checkbox group with
 * no built-in date picker. Confirmed shipped 2026-08-07:
 * [BCG_DATE_QUESTION_CODE]/[OPV_DATE_QUESTION_CODE]/[HEPATITIS_B_DATE_QUESTION_CODE]/
 * [VITAMIN_K_DATE_QUESTION_CODE] are now separate `input_type: "date"` fields, each gated visible
 * by the `contains` `visibleWhen` operator against [VACCINATION_QUESTION_CODE]'s multiselect
 * answer — the exact same pattern already shipped for [TdDoseQuestionCodes]. The backend also went
 * beyond what was asked and added a server-side `REQUIRED_IF_SELECTED` cross-field rule, so a
 * checked vaccine with no date now 422s at submission, not just fails client-side validation.
 *
 * These 4 `question_code`s are spelled identically to [VACCINATION_QUESTION_CODE]'s multiselect
 * `value_code`s of the same name. That is not a collision, for the same reason documented on
 * [TdDoseQuestionCodes]: a `value_code` lives inside [FormAnswers.multiValues]'s list, a
 * `question_code` is a key into [FormAnswers.singleValues] — different maps, so both can hold
 * `"bcg_date"` at once without clashing.
 */
object VaccinationAtBirthQuestionCodes {

  const val VACCINATION_QUESTION_CODE = "vaccination_taken_at_birth"
  const val BCG_DATE_QUESTION_CODE = "bcg_date"
  const val OPV_DATE_QUESTION_CODE = "opv_date"
  const val HEPATITIS_B_DATE_QUESTION_CODE = "hepatitis_b_date"
  const val VITAMIN_K_DATE_QUESTION_CODE = "vitamin_k_date"
  const val NONE_VALUE_CODE = "none"

  /**
   * The backend declares all 4 date fields `required: false` (correct at the schema level — each
   * is only mandatory when its own checkbox is checked, and the schema has no per-answer
   * conditional "required" concept). Same as [TdDoseQuestionCodes], the app enforces this
   * client-side too: whenever one of these fields is visible (i.e. its checkbox IS checked, per
   * the `contains` gating in [FormVisibilityEvaluator]), it is treated as required by both
   * [org.armman.sakhi.ui.forms.RequiredFieldMarker] (so the Sakhi sees the red `*`) and
   * [org.armman.sakhi.ui.childregistration.DynamicChildRegistrationViewModel
   * .fieldsAnsweredAndInRange] (so Next/Submit actually blocks on it) — never by relaxing the
   * schema's own `required` flag, only by adding to it. This mirrors what the backend now also
   * enforces server-side (`REQUIRED_IF_SELECTED`), so a Sakhi who somehow got past the client
   * check would still be caught at submit rather than silently losing the date.
   */
  val CONDITIONALLY_REQUIRED_DATE_QUESTION_CODES: Set<String> = setOf(
    BCG_DATE_QUESTION_CODE,
    OPV_DATE_QUESTION_CODE,
    HEPATITIS_B_DATE_QUESTION_CODE,
    VITAMIN_K_DATE_QUESTION_CODE,
  )
}
