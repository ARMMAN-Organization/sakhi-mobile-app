package org.armman.sakhi.data.forms

/**
 * Outcome of one `PATCH /form-submissions/:id/answers` call — deliberately its own sealed type
 * rather than a plain [Result], since the two failure shapes the backend documents need distinct
 * user-facing copy (see each variant's doc) and neither is a generic "something went wrong."
 */
sealed interface FieldEditResult {
  data object Success : FieldEditResult

  /** `400 VALIDATION_ERROR` — a submitted `fieldCode` isn't a real question on this form at all
   * (typo, wrong form code). [message] is the backend's own sentence, e.g. `"Unknown fieldCode(s)
   * for form \"MOTHER_REGISTRATION\": not_a_real_field."` — surfaced verbatim per the backend
   * contract rather than a generic failure, since it already names the offending field(s). */
  data class UnknownFieldCodes(val message: String) : FieldEditResult

  /** `422 UNPROCESSABLE` — the `fieldCode` is real but not on the post-submission-editable
   * allowlist for this form code (e.g. `lmp_date` on `MOTHER_REGISTRATION`, or anything at all on
   * `ANC_VISIT`/`POSTPARTUM_VISIT`). Same "surface verbatim" reasoning as [UnknownFieldCodes]. */
  data class NotEditable(val message: String) : FieldEditResult

  /** Anything else — connectivity, 5xx, an unexpected shape. [message] is best-effort diagnostic
   * text, not guaranteed to be sentence-worthy; callers should fall back to a generic "couldn't
   * save, try again" banner rather than rendering this raw. */
  data class Failed(val message: String?) : FieldEditResult
}

/**
 * Boundary for CR-Registration-Edit's field-correction feature (tasks 10/11 of the LMP/Reopen/
 * Referral/Audit gap analysis) — a thin wrapper over `PATCH /form-submissions/:id/answers`, kept
 * separate from [DynamicFormDraftRepository]/[org.armman.sakhi.data.childregistration
 * .ChildFormDraftRepository] because this endpoint is form-code-agnostic (works the same for
 * MOTHER_REGISTRATION, CHILD_REGISTRATION, and every other form on the backend's allowlist), while
 * those two repositories are each pinned to one specific form's submission flow.
 */
interface FieldEditsRepository {
  /**
   * [edits] is fieldCode → new value, never empty (the backend requires 1-20 items) and never
   * containing a value the caller intends as "clear this field" — the contract does not support
   * clearing, only setting. All-or-nothing: a single bad fieldCode fails the whole call, nothing
   * is partially applied server-side.
   */
  suspend fun submitEdits(submissionId: String, edits: Map<String, String>): FieldEditResult
}
