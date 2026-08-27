package org.armman.sakhi.data.delivery

/**
 * Outcome of [DeliveryFormDraftRepository.submitDraft] — the delivery-form twin of
 * [org.armman.sakhi.data.adhocform.AdHocFormSubmitResult].
 */
sealed interface DeliveryFormSubmitResult {
  /** Offline: saved locally, queued for background sync — safe to navigate. The session stays at
   * [DeliverySessionStep.DELIVERY_FORM] (no [DeliverySessionEntity] row is written yet — see that
   * class's own doc) until a later sync actually succeeds. */
  data object QueuedOffline : DeliveryFormSubmitResult

  /** Online: the backend confirmed the submission, and the [DeliverySessionEntity] row now
   * reflects it. [childBeneficiaryIds] is exactly
   * [org.armman.sakhi.data.forms.SubmissionResponseData.childBeneficiaryIds] — null means no live
   * birth (go straight to PP1), a non-null list is who still needs registering. */
  data class Synced(val childBeneficiaryIds: List<String>?) : DeliveryFormSubmitResult

  /** Online: the immediate attempt failed. The draft still stays queued in the background (it was
   * saved locally before this attempt ran). */
  data class Failed(val message: String) : DeliveryFormSubmitResult
}
