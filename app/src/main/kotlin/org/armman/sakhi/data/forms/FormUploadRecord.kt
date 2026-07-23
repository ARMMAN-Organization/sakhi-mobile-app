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
)
