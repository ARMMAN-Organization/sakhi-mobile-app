package org.armman.sakhi.data.forms

/**
 * Q44 ("Has the women received Td dose?") question/value codes.
 *
 * Backend request `td-dose-dates-schema-gap.md` (raised 2026-08-06) asked ARMMAN to add 3 real
 * date fields since the published schema had nowhere to store a Td dose date. Confirmed shipped
 * the same day: [TD_1_DATE_QUESTION_CODE]/[TD_2_DATE_QUESTION_CODE]/[TD_BOOSTER_DATE_QUESTION_CODE]
 * are now separate `input_type: "date"` fields, gated visible by a NEW `contains` `visibleWhen`
 * operator against [TD_DOSE_QUESTION_CODE]'s multiselect answer (see [FormVisibilityEvaluator]) —
 * option 1 from that doc, not the client-side-only fallback.
 *
 * These 3 `question_code`s are spelled identically to [TD_DOSE_QUESTION_CODE]'s multiselect
 * `value_code`s of the same name. That is not a collision: a `value_code` lives inside
 * [FormAnswers.multiValues]'s list, a `question_code` is a key into [FormAnswers.singleValues] —
 * different maps, so both can hold `"td_1_date"` at once without clashing.
 */
object TdDoseQuestionCodes {

  const val TD_DOSE_QUESTION_CODE = "has_the_women_received_td_dose"
  const val TD_1_DATE_QUESTION_CODE = "td_1_date"
  const val TD_2_DATE_QUESTION_CODE = "td_2_date"
  const val TD_BOOSTER_DATE_QUESTION_CODE = "td_booster_date"
  const val NONE_RECEIVED_YET_VALUE_CODE = "none_received_yet"

  /**
   * The backend declares all 3 date fields `required: false` (correct at the schema level — each
   * is only mandatory when its checkbox is checked, and the schema has no per-answer conditional
   * "required" concept). Per bharath's explicit call (2026-08-06), the app enforces this
   * client-side instead: whenever one of these fields is visible (i.e. its checkbox IS checked,
   * per the `contains` gating above), it is treated as required by both
   * [RequiredFieldMarker] (so the Sakhi sees the red `*`) and
   * [DynamicMotherRegistrationViewModel.fieldsAnsweredAndInRange] (so Next/Submit actually blocks
   * on it) — never by relaxing the schema's own `required` flag, only by adding to it.
   */
  val CONDITIONALLY_REQUIRED_DATE_QUESTION_CODES: Set<String> = setOf(
    TD_1_DATE_QUESTION_CODE,
    TD_2_DATE_QUESTION_CODE,
    TD_BOOSTER_DATE_QUESTION_CODE,
  )
}
