package org.armman.sakhi.data.visitform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/** In-memory fake mirroring `FakeDynamicFormDraftDao`'s conventions, so [RoomVisitFormDraftRepository]
 * and [VisitFormSyncExecutor] are unit-testable without a real Room database. [observeAll] is a hot
 * [MutableStateFlow] re-emitted on every [upsert], mirroring how real Room invalidates its
 * observable queries. */
class FakeVisitFormDraftDao : VisitFormDraftDao {
  private val rows = mutableMapOf<String, VisitFormDraftEntity>()
  private val allFlow = MutableStateFlow<List<VisitFormDraftEntity>>(emptyList())

  override suspend fun upsert(entity: VisitFormDraftEntity) {
    rows[entity.localScheduleUuid] = entity
    emitCurrent()
  }

  override suspend fun getByLocalScheduleUuid(localScheduleUuid: String): VisitFormDraftEntity? =
    rows[localScheduleUuid]

  override suspend fun getByLocalScheduleUuids(localScheduleUuids: List<String>): List<VisitFormDraftEntity> =
    localScheduleUuids.mapNotNull { rows[it] }

  override suspend fun getPendingSync(): List<VisitFormDraftEntity> =
    rows.values
      .filter { it.syncStatus == EnrollmentSyncStatus.PENDING || it.syncStatus == EnrollmentSyncStatus.FAILED }
      .sortedBy { it.createdAtEpochMillis }

  override suspend fun getRiskAssessmentPending(): List<VisitFormDraftEntity> =
    rows.values
      .filter {
        it.syncStatus == EnrollmentSyncStatus.SYNCED &&
          (it.riskAssessmentStatus == EnrollmentSyncStatus.PENDING || it.riskAssessmentStatus == EnrollmentSyncStatus.FAILED)
      }
      .sortedBy { it.createdAtEpochMillis }

  override suspend fun reclaimStaleSyncing(): Int {
    val stale = rows.values.filter { it.syncStatus == EnrollmentSyncStatus.SYNCING }
    stale.forEach { rows[it.localScheduleUuid] = it.copy(syncStatus = EnrollmentSyncStatus.PENDING) }
    if (stale.isNotEmpty()) emitCurrent()
    return stale.size
  }

  override suspend fun getAll(): List<VisitFormDraftEntity> = sortedRows()

  override fun observeAll(): Flow<List<VisitFormDraftEntity>> = allFlow

  private fun emitCurrent() {
    allFlow.value = sortedRows()
  }

  private fun sortedRows(): List<VisitFormDraftEntity> =
    rows.values.sortedByDescending { it.createdAtEpochMillis }
}
