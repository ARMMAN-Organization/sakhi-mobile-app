package org.armman.sakhi.data.forms

import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/**
 * UI-facing projection of a [DynamicFormDraftEntity] for the Home screen's "Forms Uploaded"
 * sync-status modal. Deliberately excludes the entity's remote IDs and [DynamicFormDraftEntity]'s
 * `lastErrorMessage` — neither is meaningful to the Sakhi-facing view — and never touches the
 * encrypted [FormAnswers] payload, so no PII crosses into the UI layer via this model.
 *
 * [formCode] (e.g. `"MOTHER_REGISTRATION"`) is not PII — it's the same form-category identifier
 * used to request the schema/submit the form — and is what the modal groups records by, since the
 * Figma reference renders one card per form *category*, not one per individual submission.
 */
data class FormUploadRecord(
  val localBeneficiaryId: String,
  val formCode: String,
  val syncStatus: EnrollmentSyncStatus,
  val createdAtEpochMillis: Long,
  /**
   * Non-null when this draft was rejected as a possible duplicate AND the backend said the earlier
   * pregnancy is complete (SRS FR-S-2.5) — the value is that earlier case's server id, and its
   * presence is what lets Home ask "is this a new pregnancy?" for a draft rejected during an upload.
   *
   * Not PII: a server-assigned case id, the same kind of identifier as [localBeneficiaryId]. Null on
   * every other status, and on the Children Register queue, where this branch cannot occur.
   */
  val pendingNewPregnancyBeneficiaryId: String? = null,
)
