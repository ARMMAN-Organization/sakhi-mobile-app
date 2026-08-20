package org.armman.sakhi.data.delivery

/**
 * Per-draft outcome of an IMMEDIATE, single-item sync attempt via
 * [DeliveryChildRegistrationSyncExecutor.runOne] — used by
 * [RoomDeliveryChildRegistrationDraftRepository.submitDraft] to report the real backend result
 * right after Submit. Mirrors [DeliveryFormSyncItemResult]'s rationale exactly.
 */
sealed interface DeliveryChildRegistrationSyncItemResult {
  data object Synced : DeliveryChildRegistrationSyncItemResult

  data class Failed(val message: String) : DeliveryChildRegistrationSyncItemResult

  /** A transient `IOException` — no `NotYetSynced`-style case here, since (unlike the mother's
   * `DELIVERY_VISIT` submission) this coordinator never needs to resolve a server beneficiary id
   * from local schedule rows — [DeliveryChildRegistrationDraftEntity.serverBeneficiaryId] is
   * already known and carried on the draft itself. */
  data class Retryable(val message: String?) : DeliveryChildRegistrationSyncItemResult
}
