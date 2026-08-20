package org.armman.sakhi.data.delivery

/**
 * Local store for Delivery Event Session progress (CR-042). Read-heavy and local-only, same shape
 * as [org.armman.sakhi.data.schedule.VisitScheduleRepository]'s own doc: this table has no upload
 * path of its own (see [DeliverySessionEntity]'s doc for why), so there is no sync method here.
 */
interface DeliverySessionRepository {

  /** Creates or updates a session row. All-or-nothing, same convention as every other queue. */
  suspend fun save(session: DeliverySessionEntity)

  suspend fun getBySessionUuid(localSessionUuid: String): DeliverySessionEntity?

  /** The beneficiary's one resumable (non-[DeliverySessionStep.DONE]) session, or null if she has
   * none in progress — either because she has never started one, or because her last one already
   * completed. */
  suspend fun getActiveForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity?

  /** The beneficiary's own delivery session regardless of step — including a [DeliverySessionStep.DONE]
   * one [getActiveForBeneficiary] would hide. Use this when what's needed is a fact recorded on
   * the original session (e.g. [DeliverySessionEntity.deliverySubmissionLocalUuid] for
   * [DeliveryToNeonatalPrefill]), not "is a session currently in progress". */
  suspend fun getMostRecentForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity?
}
