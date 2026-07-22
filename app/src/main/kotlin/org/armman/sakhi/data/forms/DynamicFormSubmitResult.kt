package org.armman.sakhi.data.forms

/**
 * Outcome of [DynamicFormDraftRepository.submitDraft] — the dynamic-forms twin of
 * [org.armman.sakhi.data.enrollment.EnrollmentSubmitResult]. Distinguishes a confirmed backend
 * result (only obtainable while online) from a purely local save queued for background sync
 * (offline), so the ViewModel knows whether it's safe to navigate away yet.
 */
sealed interface DynamicFormSubmitResult {
  /** Offline: saved locally, queued for background sync — safe to navigate. */
  data object QueuedOffline : DynamicFormSubmitResult

  /** Online: the backend confirmed both the beneficiary and the form submission were created. */
  data object Synced : DynamicFormSubmitResult

  /** Online: backend rejected as a possible duplicate (SRS FR-S-2.4/2.5). */
  data class DuplicateConflict(val message: String?) : DynamicFormSubmitResult

  /** Online: backend rejected for any other reason (validation, mapping, server error). */
  data class Failed(val message: String?) : DynamicFormSubmitResult
}
