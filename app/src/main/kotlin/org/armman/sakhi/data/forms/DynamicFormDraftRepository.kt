package org.armman.sakhi.data.forms

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Offline-first persistence boundary for a dynamic-form draft (CR-018) — the twin of
 * [org.armman.sakhi.data.enrollment.EnrollmentRepository] for this feature. A save always
 * succeeds locally regardless of connectivity; actual network submission is
 * [DynamicFormSyncExecutor]'s job via a scheduled [DynamicFormSyncScheduler] run, not this
 * repository's.
 */
interface DynamicFormDraftRepository {

  /** Saves (or overwrites — re-submit safety) the draft keyed by [localBeneficiaryId]. Always
   * succeeds locally and never touches the network: uploading happens only on the Sakhi's manual
   * Data Upload action (SRS §3A.1). Prefer [submitDraft] from the Submit button so a
   * validation/conflict error while online is caught before the user navigates away. */
  suspend fun saveDraft(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): Result<Unit>

  /**
   * Saves the draft locally, then — only while online — attempts the real backend submission
   * (both API calls) immediately and returns its outcome, so the caller can keep the user
   * on-screen and show the actual error instead of navigating away on a local save that says
   * nothing about the backend. While offline it saves locally and leaves the draft PENDING for the
   * next manual Data Upload ([DynamicFormSubmitResult.QueuedOffline]) — this never blocks on
   * connectivity, and never schedules a background upload of its own.
   */
  suspend fun submitDraft(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): DynamicFormSubmitResult

  /**
   * Records the Sakhi's confirmation of an FR-S-2.5 "is this a new pregnancy?" prompt for
   * [localBeneficiaryId], then retries the submission.
   *
   * The acknowledgement is persisted on the draft (not held in memory), so a draft that was rejected
   * during a manual Data Upload can be confirmed from the Home screen and will carry the
   * acknowledgement on its next upload even if the app is restarted in between. While online the
   * retry happens immediately and its real outcome is returned; offline the draft goes back to
   * PENDING and [DynamicFormSubmitResult.QueuedOffline] is returned.
   *
   * Returns [DynamicFormSubmitResult.Failed] if there is no draft or stored payload for this id —
   * there is nothing to resubmit, and silently reporting success would lose the registration.
   */
  suspend fun confirmNewPregnancy(
    localBeneficiaryId: String,
    existingBeneficiaryId: String,
  ): DynamicFormSubmitResult

  /**
   * Records that the Sakhi declined an FR-S-2.5 new-pregnancy prompt for [localBeneficiaryId].
   *
   * Only the stored prompt is cleared — the draft stays in DUPLICATE_CONFLICT and its answers are
   * untouched, so she can still correct a mistyped name or LMP and submit again. Without this, a
   * prompt she already answered "no" to would keep reappearing on Home after every upload.
   */
  suspend fun dismissNewPregnancyPrompt(localBeneficiaryId: String)

  /** All CR-018 Mother Registration drafts, newest first, for the Home screen's "Forms Uploaded"
   * sync-status modal. The static enrollment flow is out of scope — it's being deprecated in
   * favor of this dynamic form, per product direction. */
  suspend fun getUploadRecords(): List<FormUploadRecord>

  /** Observable version of [getUploadRecords] — re-emits whenever a draft is added or its sync
   * status changes, so the Home upload modal and Data Upload badge reflect sync progress live
   * (including background [DynamicFormSyncWorker] runs) without the user reopening the screen. */
  fun observeUploadRecords(): Flow<List<FormUploadRecord>>

  /**
   * CR-Registration-Edit: looks up the local draft for [beneficiaryId] — the Beneficiary Profile
   * screen's own id, which is the LOCAL beneficiaryId for a beneficiary enrolled on this device
   * (the common case, whether synced yet or not) or the SERVER beneficiaryId for one this device
   * never locally enrolled at all (see BeneficiaryProfileRepository.getBeneficiary's own doc for
   * why the screen mixes the two). Tries [beneficiaryId] as a local id first, then as a remote id,
   * so the Edit stub finds the right row either way. Null if neither lookup matches — the caller
   * falls back to a "can't edit here" state.
   */
  suspend fun getEditableSubmission(beneficiaryId: String): EditableSubmissionInfo?

  /**
   * Merges [edits] into the locally stored answers for [localBeneficiaryId] after a successful
   * `PATCH /form-submissions/:id/answers` — so the next time the Sakhi opens Edit, the fields show
   * what she just saved, not the original registration answers. Never touches [syncStatus] or
   * queues a re-upload: the edit already landed on the server directly, this is local-cache
   * bookkeeping only.
   */
  suspend fun applyFieldEdits(localBeneficiaryId: String, edits: Map<String, String>)
}
