package org.armman.sakhi.data.childregistration

import kotlinx.coroutines.flow.Flow
import org.armman.sakhi.data.forms.EditableSubmissionInfo
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import java.time.LocalDate

/**
 * Offline-first persistence boundary for a Children Register draft (CR-020) — the standalone twin
 * of [org.armman.sakhi.data.forms.DynamicFormDraftRepository]. A save always succeeds locally
 * regardless of connectivity; actual network submission is [ChildFormSyncExecutor]'s job via a
 * scheduled [ChildFormSyncScheduler] run, not this repository's.
 */
interface ChildFormDraftRepository {

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
   * Saves the draft locally, then — only while online — attempts the real backend submission (both
   * API calls) immediately and returns its outcome, so the caller can keep the user on-screen and
   * show the actual error instead of navigating away on a local save. While offline it saves
   * locally and leaves the draft PENDING for the next manual Data Upload
   * ([ChildFormSubmitResult.QueuedOffline]) — never blocks on connectivity, and never schedules a
   * background upload of its own.
   */
  suspend fun submitDraft(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): ChildFormSubmitResult

  /** All CR-020 Children Register drafts, newest first, for the Home screen's "Forms Uploaded"
   * sync-status modal. Reuses [FormUploadRecord] — its shape is form-agnostic. */
  suspend fun getUploadRecords(): List<FormUploadRecord>

  /** Observable version of [getUploadRecords] — re-emits whenever a draft is added or its sync
   * status changes, so the Home upload modal reflects this queue's progress live during a manual
   * sync run. Merged with the other queues by
   * [org.armman.sakhi.data.sync.UploadRecordsSource]. */
  fun observeUploadRecords(): Flow<List<FormUploadRecord>>

  /** Mirrors [org.armman.sakhi.data.forms.DynamicFormDraftRepository.getEditableSubmission] for
   * the child flow (CR-Registration-Edit) — including trying [beneficiaryId] as a local id before
   * a remote one. */
  suspend fun getEditableSubmission(beneficiaryId: String): EditableSubmissionInfo?

  /** Mirrors [org.armman.sakhi.data.forms.DynamicFormDraftRepository.applyFieldEdits] for the
   * child flow (CR-Registration-Edit). */
  suspend fun applyFieldEdits(localBeneficiaryId: String, edits: Map<String, String>)
}
