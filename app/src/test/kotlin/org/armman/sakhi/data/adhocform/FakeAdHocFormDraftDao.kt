package org.armman.sakhi.data.adhocform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/** In-memory fake mirroring `FakeVisitFormDraftDao`'s conventions, so [RoomAdHocFormDraftRepository]
 * and [AdHocFormSyncExecutor] are unit-testable without a real Room database. [observeAll] is a hot
 * [MutableStateFlow] re-emitted on every [upsert], mirroring how real Room invalidates its
 * observable queries. */
class FakeAdHocFormDraftDao : AdHocFormDraftDao {
  private val rows = mutableMapOf<String, AdHocFormDraftEntity>()
  private val allFlow = MutableStateFlow<List<AdHocFormDraftEntity>>(emptyList())

  override suspend fun upsert(entity: AdHocFormDraftEntity) {
    rows[entity.localFormInstanceUuid] = entity
    emitCurrent()
  }

  override suspend fun getByLocalFormInstanceUuid(localFormInstanceUuid: String): AdHocFormDraftEntity? =
    rows[localFormInstanceUuid]

  override suspend fun getPendingSync(): List<AdHocFormDraftEntity> =
    rows.values
      .filter { it.syncStatus == EnrollmentSyncStatus.PENDING || it.syncStatus == EnrollmentSyncStatus.FAILED }
      .sortedBy { it.createdAtEpochMillis }

  override suspend fun reclaimStaleSyncing(): Int {
    val stale = rows.values.filter { it.syncStatus == EnrollmentSyncStatus.SYNCING }
    stale.forEach { rows[it.localFormInstanceUuid] = it.copy(syncStatus = EnrollmentSyncStatus.PENDING) }
    if (stale.isNotEmpty()) emitCurrent()
    return stale.size
  }

  override suspend fun getAll(): List<AdHocFormDraftEntity> = sortedRows()

  override fun observeAll(): Flow<List<AdHocFormDraftEntity>> = allFlow

  private fun emitCurrent() {
    allFlow.value = sortedRows()
  }

  private fun sortedRows(): List<AdHocFormDraftEntity> =
    rows.values.sortedByDescending { it.createdAtEpochMillis }
}
