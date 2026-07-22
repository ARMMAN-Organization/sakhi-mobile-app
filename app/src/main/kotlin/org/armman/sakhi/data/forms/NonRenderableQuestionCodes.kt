package org.armman.sakhi.data.forms

/**
 * `question_code`s that must never render as a fillable input, regardless of `input_type` —
 * confirmed against the real v6 `active-version` response (CR-018 follow-up).
 *
 * `beneficiary_id` is the server-assigned id from `beneficiary_cases.beneficiary_id` (see
 * [DynamicFormSubmissionCoordinator]) — it does not exist yet while the Sakhi is filling out this
 * form, so unlike a `computedFrom` field (which at least has a formula to eventually compute
 * client-side), there is nothing to show here at all until *after* submission. The schema
 * currently declares it as a plain `number` field with no `computedFrom`/hidden marker, so without
 * this special case it would render as an empty box the Sakhi could type a value into — which
 * would never be used (the real id always comes back from the server response).
 *
 * Same risk as [GeographyQuestionCodes]: if the backend ever renames this `question_code`, this
 * silently stops applying (the field reappears as an editable input) rather than failing loudly.
 */
object NonRenderableQuestionCodes {
  const val BENEFICIARY_ID = "beneficiary_id"

  val ALL: Set<String> = setOf(BENEFICIARY_ID)
}
