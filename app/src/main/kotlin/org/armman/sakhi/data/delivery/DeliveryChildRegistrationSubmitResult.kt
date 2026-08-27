package org.armman.sakhi.data.delivery

/**
 * Outcome of [DeliveryChildRegistrationDraftRepository.submitDraft] — the delivery-session
 * `CHILD_REGISTRATION` twin of [DeliveryFormSubmitResult].
 */
sealed interface DeliveryChildRegistrationSubmitResult {
  /** Offline: saved locally, queued for background sync — safe to navigate. The session's
   * [DeliverySessionEntity] is untouched until a later sync actually succeeds. */
  data object QueuedOffline : DeliveryChildRegistrationSubmitResult

  /** Online: the backend confirmed the submission, and the [DeliverySessionEntity] row now
   * reflects it — see [DeliveryChildRegistrationSubmissionCoordinator]'s own doc. */
  data object Synced : DeliveryChildRegistrationSubmitResult

  /** Online: the immediate attempt failed. The draft still stays queued in the background (it was
   * saved locally before this attempt ran). */
  data class Failed(val message: String) : DeliveryChildRegistrationSubmitResult
}
