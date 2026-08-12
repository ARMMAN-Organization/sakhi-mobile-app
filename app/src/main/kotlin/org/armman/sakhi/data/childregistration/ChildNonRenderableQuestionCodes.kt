package org.armman.sakhi.data.childregistration

import org.armman.sakhi.data.forms.REGISTRATION_DATE_QUESTION_CODES

/**
 * `question_code`s in the CHILD_REGISTRATION schema that must never render as a fillable input,
 * regardless of `input_type` — the child twin of
 * [org.armman.sakhi.data.forms.NonRenderableQuestionCodes].
 *
 * `beneficiary_id` is the server-assigned id from `beneficiary_cases.beneficiary_id` (see
 * [ChildRegistrationSubmissionCoordinator]) — it does not exist yet while the Sakhi is filling out
 * this form, so it must not render as an editable box.
 *
 * `unique_id` is a `computedFrom: "UNIQUE_ID"` field whose formula is unconfirmed with the backend
 * (see [org.armman.sakhi.data.forms.FormComputedFieldEvaluator]); it is additionally hidden here so
 * the Sakhi is never shown a permanently-blank read-only box. Risk flagged: because its formula is
 * unimplemented, `unique_id` is submitted with no value — if the backend types it `required` on the
 * CHILD_REGISTRATION submissions endpoint, this could 422 until the formula is supplied.
 *
 * `project_name` and `registration_date` (bharath, 2026-08-07 — "only hide, don't do anything
 * else") match the mother flow's existing behaviour
 * ([org.armman.sakhi.data.forms.NonRenderableQuestionCodes]): both are still auto-filled and
 * submitted exactly as before (`project_name` via [org.armman.sakhi.data.forms
 * .GeographyFieldOptionsResolver], `registration_date` via
 * [org.armman.sakhi.data.forms.RegistrationDatePrefill]) — this only removes them from the visible
 * field list, no submission/validation behavior changes.
 *
 * Same risk as [org.armman.sakhi.data.forms.GeographyQuestionCodes]: if the backend ever renames
 * one of these `question_code`s, this silently stops applying (the field reappears as an editable
 * input) rather than failing loudly.
 */
object ChildNonRenderableQuestionCodes {
  const val BENEFICIARY_ID = "beneficiary_id"
  const val UNIQUE_ID = "unique_id"
  const val PROJECT_NAME = "project_name"

  val ALL: Set<String> = setOf(BENEFICIARY_ID, UNIQUE_ID, PROJECT_NAME) + REGISTRATION_DATE_QUESTION_CODES
}
