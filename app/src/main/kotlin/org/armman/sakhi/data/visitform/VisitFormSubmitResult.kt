package org.armman.sakhi.data.visitform

/**
 * Outcome of [VisitFormDraftRepository.submitDraft] — the visit-form twin of
 * [org.armman.sakhi.data.forms.DynamicFormSubmitResult]. No `DuplicateConflict` variant: visit
 * submissions have no analogous FR-S-2.4/2.5 concept.
 */
sealed interface VisitFormSubmitResult {
  /** Offline: saved locally, queued for background sync — safe to navigate. */
  data object QueuedOffline : VisitFormSubmitResult

  /** Online: the backend confirmed both the visit instance and the form submission were created. */
  data object Synced : VisitFormSubmitResult

  /**
   * Online: the immediate attempt failed. [message] is [VisitFormSubmissionException.userMessage]
   * (or a generic fallback) — the same sentence shown today, unchanged by CR-026b.
   *
   * The draft still stays queued in the background (it was saved locally before this attempt ran)
   * — that part IS new: before CR-026b there was no queue at all, so a failed immediate attempt
   * was simply lost unless the Sakhi noticed and resubmitted by hand. Now the next manual Data
   * Upload retries it automatically, on top of the same immediate feedback as before.
   */
  data class Failed(val message: String) : VisitFormSubmitResult
}
