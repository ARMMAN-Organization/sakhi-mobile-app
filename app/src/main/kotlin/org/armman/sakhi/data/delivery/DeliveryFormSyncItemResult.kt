package org.armman.sakhi.data.delivery

/**
 * Per-draft outcome of an IMMEDIATE, single-item sync attempt via
 * [DeliveryFormSyncExecutor.runOne] — used by [RoomDeliveryFormDraftRepository.submitDraft] to
 * report the real backend result right after Submit. Mirrors
 * [org.armman.sakhi.data.adhocform.AdHocFormSyncItemResult]'s rationale exactly.
 */
sealed interface DeliveryFormSyncItemResult {
  /** [childBeneficiaryIds] is threaded through so the caller can react to it without a second
   * lookup — same value [DeliveryFormSubmissionCoordinator.submit] already wrote onto the
   * [DeliverySessionEntity] row before returning. */
  data class Synced(val childBeneficiaryIds: List<String>?) : DeliveryFormSyncItemResult

  data class Failed(val message: String) : DeliveryFormSyncItemResult

  /** Includes [DeliveryFormSubmissionException.NotYetSynced] — the mother hasn't finished syncing
   * yet, not a permanent problem — as well as a transient `IOException`. */
  data class Retryable(val message: String?) : DeliveryFormSyncItemResult
}
