package org.armman.sakhi.data.forms

import org.armman.sakhi.data.enrollment.DuplicateOutcome

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

  /**
   * Online: backend rejected as a possible duplicate (SRS FR-S-2.4/2.5).
   *
   * [outcome] decides what the Sakhi sees: a hard duplicate is a dead end she cannot override, a
   * new-pregnancy prompt is a question she can confirm — after which
   * [DynamicFormDraftRepository.confirmNewPregnancy] resubmits with the acknowledgement.
   */
  data class DuplicateConflict(val outcome: DuplicateOutcome) : DynamicFormSubmitResult

  /**
   * Online: backend rejected for any other reason (validation, mapping, server error).
   *
   * [fieldErrors] is populated only for a `400 VALIDATION_ERROR` on `POST /beneficiaries` — the
   * backend's per-field messages keyed by dotted DTO path (`pii.firstName`, …), still to be mapped
   * to `question_code`s by [BeneficiaryFieldErrorMapper]. Empty for `422 UNPROCESSABLE`, duplicate
   * conflicts, mapping failures, and server errors, which have no field attribution and stay a
   * page-level banner.
   */
  data class Failed(
    val message: String?,
    val fieldErrors: Map<String, String> = emptyMap(),
  ) : DynamicFormSubmitResult
}
