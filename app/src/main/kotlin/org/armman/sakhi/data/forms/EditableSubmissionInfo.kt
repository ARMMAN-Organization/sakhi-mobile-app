package org.armman.sakhi.data.forms

/**
 * Everything [org.armman.sakhi.ui.beneficiaryprofile.BeneficiaryFieldEditViewModel] needs to edit
 * an already-submitted MOTHER_REGISTRATION/CHILD_REGISTRATION form via `PATCH
 * /form-submissions/:id/answers` — looked up by the server-assigned beneficiary id the Beneficiary
 * Profile screen already has (see [DynamicFormDraftDao.getByRemoteBeneficiaryId] /
 * [org.armman.sakhi.data.childregistration.ChildFormDraftDao.getByRemoteBeneficiaryId]).
 *
 * Deliberately shared between the mother and child drafts, which otherwise use separate tables/
 * repositories end to end (see [DynamicFormDraftEntity]'s own doc for why the split exists) — the
 * edit screen doesn't care which table a row came from, only that it has a submission to target
 * and the answers to pre-fill from.
 */
data class EditableSubmissionInfo(
  /** The draft's own local key — needed to write the edited answers back with
   * [DynamicFormDraftRepository.applyFieldEdits] / [org.armman.sakhi.data.childregistration
   * .ChildFormDraftRepository.applyFieldEdits] so a later edit session pre-fills the latest values,
   * not what was originally submitted. */
  val localBeneficiaryId: String,
  val formVersionId: String,
  /** `PATCH /form-submissions/:id/answers`'s path id. Null means this beneficiary has never
   * synced (or synced before this field existed) — the caller must fall back to a
   * "can't edit yet, this record hasn't finished uploading" state rather than call the endpoint
   * with nothing to target. */
  val remoteSubmissionId: String?,
  val answers: FormAnswers,
)
