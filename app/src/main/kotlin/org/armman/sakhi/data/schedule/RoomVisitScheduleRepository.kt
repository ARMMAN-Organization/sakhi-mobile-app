package org.armman.sakhi.data.schedule

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [VisitScheduleRepository]. A thin pass-through by intent — schedule *logic* belongs
 * in the generator (CR-022b–d), not here, so that the generator stays a pure function testable
 * without a database.
 */
@Singleton
class RoomVisitScheduleRepository @Inject constructor(
  private val dao: VisitScheduleDao,
) : VisitScheduleRepository {

  override suspend fun saveGenerated(schedules: List<VisitScheduleEntity>) {
    if (schedules.isEmpty()) return
    dao.upsertAll(schedules)
  }

  override suspend fun getForBeneficiary(localBeneficiaryId: String): List<VisitScheduleEntity> =
    dao.getForBeneficiary(localBeneficiaryId)

  override suspend fun getByLocalScheduleUuid(localScheduleUuid: String): VisitScheduleEntity? =
    dao.getByLocalUuid(localScheduleUuid)

  override suspend fun getActiveForBeneficiary(
    localBeneficiaryId: String,
  ): List<VisitScheduleEntity> = dao.getActiveForBeneficiary(localBeneficiaryId)

  override fun observeActiveForBeneficiary(
    localBeneficiaryId: String,
  ): Flow<List<VisitScheduleEntity>> = dao.observeActiveForBeneficiary(localBeneficiaryId)

  override suspend fun getOpenByType(
    localBeneficiaryId: String,
    visitType: VisitCodeType,
  ): List<VisitScheduleEntity> = dao.getOpenByType(localBeneficiaryId, visitType)

  override suspend fun hasSchedule(localBeneficiaryId: String): Boolean =
    dao.countForBeneficiary(localBeneficiaryId) > 0

  override suspend fun hasScheduleOfType(
    localBeneficiaryId: String,
    visitType: VisitCodeType,
  ): Boolean = dao.countForBeneficiaryAndType(localBeneficiaryId, visitType) > 0

  override suspend fun getUnsynced(): List<VisitScheduleEntity> = dao.getUnsynced()

  override fun observeUnsyncedCount(): Flow<Int> = dao.observeUnsyncedCount()

  override suspend fun markSynced(localScheduleUuid: String, serverScheduleId: String) {
    dao.markSynced(localScheduleUuid, serverScheduleId)
  }

  override suspend fun attachServerBeneficiaryId(
    localBeneficiaryId: String,
    serverBeneficiaryId: String,
  ) {
    dao.attachServerBeneficiaryId(localBeneficiaryId, serverBeneficiaryId)
  }

  override suspend fun updateStatus(
    localScheduleUuid: String,
    status: VisitScheduleStatus,
    reasonCode: String?,
  ) {
    dao.updateStatus(localScheduleUuid, status, reasonCode)
  }

  override suspend fun lapseOpenAncVisits(localBeneficiaryId: String): Int =
    dao.lapseOpenAncVisits(localBeneficiaryId)

  override suspend fun lapseAllOpenVisits(localBeneficiaryId: String): Int =
    dao.lapseAllOpenVisits(localBeneficiaryId)

  override suspend fun supersedeOpenVisits(localBeneficiaryId: String): Int =
    dao.supersedeOpenVisits(localBeneficiaryId)
}
