package org.armman.sakhi.data.delivery

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDeliverySessionRepository @Inject constructor(
  private val dao: DeliverySessionDao,
) : DeliverySessionRepository {

  override suspend fun save(session: DeliverySessionEntity) = dao.upsert(session)

  override suspend fun getBySessionUuid(localSessionUuid: String): DeliverySessionEntity? =
    dao.getBySessionUuid(localSessionUuid)

  override suspend fun getActiveForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity? =
    dao.getActiveForBeneficiary(localBeneficiaryId)

  override suspend fun getMostRecentForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity? =
    dao.getMostRecentForBeneficiary(localBeneficiaryId)
}
