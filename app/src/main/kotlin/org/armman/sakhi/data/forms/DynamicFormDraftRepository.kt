package org.armman.sakhi.data.forms

import java.time.LocalDate

/**
 * Offline-first persistence boundary for a dynamic-form draft (CR-018) — the twin of
 * [org.armman.sakhi.data.enrollment.EnrollmentRepository] for this feature. A save always
 * succeeds locally regardless of connectivity; actual network submission is
 * [DynamicFormSyncExecutor]'s job via a scheduled [DynamicFormSyncScheduler] run, not this
 * repository's.
 */
interface DynamicFormDraftRepository {

  /** Saves (or overwrites — re-submit safety) the draft keyed by [localBeneficiaryId], and
   * nudges an immediate sync attempt. Always succeeds locally; does not wait for or report the
   * backend outcome — prefer [submitDraft] from the Submit button so a validation/conflict error
   * while online is caught before the user navigates away. */
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
   * nothing about the backend. While offline, saves locally and queues background sync as before
   * ([DynamicFormSubmitResult.QueuedOffline]) — this never blocks on connectivity.
   */
  suspend fun submitDraft(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): DynamicFormSubmitResult
}
