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
 * `unique_id` is a `computedFrom: "UNIQUE_ID"` field whose formula is unconfirmed with the backend
 * (see [FormComputedFieldEvaluator]); it is additionally hidden here so the Sakhi is never shown a
 * permanently-blank read-only box (the twin of [org.armman.sakhi.data.childregistration
 * .ChildNonRenderableQuestionCodes.UNIQUE_ID], already applied on the child flow). Risk flagged:
 * because its formula is unimplemented, `unique_id` is submitted with no value — if the backend
 * types it `required` on the MOTHER_REGISTRATION submissions endpoint, this could 422 until the
 * formula is supplied.
 *
 * `project_name` is populated via [GeographyFieldOptionsResolver]/the Sakhi's profile
 * (form spec row 11, "Autopopulated based on Sakhi's project") and is hidden unconditionally per
 * product decision — including for a Sakhi assigned to more than one project, where it would
 * otherwise fall back to an interactive dropdown (see [DynamicMotherRegistrationViewModel
 * .prefillAutoSelectedGeography]). Risk flagged: for a multi-project Sakhi this field now submits
 * whatever [GeographyFieldOptionsResolver] happens to resolve (or blank, if it can't resolve a
 * single value) with no way for the Sakhi to pick — revisit if multi-project Sakhis exist in
 * production.
 *
 * Same risk as [GeographyQuestionCodes]: if the backend ever renames one of these `question_code`s,
 * this silently stops applying (the field reappears as an editable input) rather than failing
 * loudly.
 */
object NonRenderableQuestionCodes {
  const val BENEFICIARY_ID = "beneficiary_id"
  const val UNIQUE_ID = "unique_id"
  const val PROJECT_NAME = "project_name"

  val ALL: Set<String> = setOf(BENEFICIARY_ID, UNIQUE_ID, PROJECT_NAME)
}
