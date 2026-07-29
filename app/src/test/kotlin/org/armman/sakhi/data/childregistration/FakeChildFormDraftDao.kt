package org.armman.sakhi.data.childregistration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/** In-memory fake mirroring `FakeDynamicFormDraftDao`'s conventions, so [ChildFormSyncExecutor]
 * (and any future repository test) is unit-testable without a real Room database. */
class FakeChildFormDraftDao : ChildFormDraftDao {
  private val rows = mutableMapOf<String, ChildFormDraftEntity>()

  /** Bumped on every write so [observeAll] re-emits, the way Room's own invalidation tracker does. */
  private val revision = MutableStateFlow(0)

  override suspend fun upsert(entity: ChildFormDraftEntity) {
    rows[entity.localBeneficiaryId] = entity
    revision.value++
  }

  override suspend fun getByLocalBeneficiaryId(localBeneficiaryId: String): ChildFormDraftEntity? =
    rows[localBeneficiaryId]

  override suspend fun getPendingSync(): List<ChildFormDraftEntity> =
    rows.values
      .filter { it.syncStatus == EnrollmentSyncStatus.PENDING || it.syncStatus == EnrollmentSyncStatus.FAILED }
      .sortedBy { it.createdAtEpochMillis }

  override suspend fun reclaimStaleSyncing(): Int {
    val stale = rows.values.filter { it.syncStatus == EnrollmentSyncStatus.SYNCING }
    stale.forEach { rows[it.localBeneficiaryId] = it.copy(syncStatus = EnrollmentSyncStatus.PENDING) }
    if (stale.isNotEmpty()) revision.value++
    return stale.size
  }

  override suspend fun getAll(): List<ChildFormDraftEntity> =
    rows.values.sortedByDescending { it.createdAtEpochMillis }

  override fun observeAll(): Flow<List<ChildFormDraftEntity>> =
    revision.map { rows.values.sortedByDescending { row -> row.createdAtEpochMillis } }
}
