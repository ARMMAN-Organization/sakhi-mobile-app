package org.armman.sakhi.data.childregistration

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

  /** Saves (or overwrites — re-submit safety) the draft keyed by [localBeneficiaryId], and nudges
   * an immediate sync attempt. Always succeeds locally; does not wait for or report the backend
   * outcome — prefer [submitDraft] from the Submit button so a validation/conflict error while
   * online is caught before the user navigates away. */
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
   * show the actual error instead of navigating away on a local save. While offline, saves locally
   * and queues background sync ([ChildFormSubmitResult.QueuedOffline]) — never blocks on
   * connectivity.
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
}
