package org.armman.sakhi.data.adhocform

/**
 * Outcome of [AdHocFormDraftRepository.submitDraft] — the ad-hoc-form twin of
 * [org.armman.sakhi.data.visitform.VisitFormSubmitResult]. No `DuplicateConflict` variant: these
 * forms have no FR-S-2.4/2.5-style duplicate-detection concept.
 */
sealed interface AdHocFormSubmitResult {
  /** Offline: saved locally, queued for background sync — safe to navigate. */
  data object QueuedOffline : AdHocFormSubmitResult

  /** Online: the backend confirmed the form submission was created. */
  data object Synced : AdHocFormSubmitResult

  /** Online: the immediate attempt failed. [message] is the one sentence to show — the draft
   * still stays queued in the background (it was saved locally before this attempt ran). */
  data class Failed(val message: String) : AdHocFormSubmitResult
}
