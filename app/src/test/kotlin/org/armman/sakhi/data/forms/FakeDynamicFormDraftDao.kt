package org.armman.sakhi.data.forms

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/** In-memory fake mirroring `FakeEnrollmentDraftDao`'s conventions, so [RoomDynamicFormDraftRepository]
 * and [DynamicFormSyncExecutor] are unit-testable without a real Room database. [observeAll] is a
 * hot [MutableStateFlow] re-emitted on every [upsert], mirroring how real Room invalidates its
 * observable queries — that's what the Home live-progress tests rely on. */
class FakeDynamicFormDraftDao : DynamicFormDraftDao {
  private val rows = mutableMapOf<String, DynamicFormDraftEntity>()
  private val allFlow = MutableStateFlow<List<DynamicFormDraftEntity>>(emptyList())

  override suspend fun upsert(entity: DynamicFormDraftEntity) {
    rows[entity.localBeneficiaryId] = entity
    emitCurrent()
  }

  override suspend fun getByLocalBeneficiaryId(localBeneficiaryId: String): DynamicFormDraftEntity? =
    rows[localBeneficiaryId]

  override suspend fun getPendingSync(): List<DynamicFormDraftEntity> =
    rows.values
      .filter { it.syncStatus == EnrollmentSyncStatus.PENDING || it.syncStatus == EnrollmentSyncStatus.FAILED }
      .sortedBy { it.createdAtEpochMillis }

  override suspend fun getAll(): List<DynamicFormDraftEntity> = sortedRows()

  override fun observeAll(): Flow<List<DynamicFormDraftEntity>> = allFlow

  private fun emitCurrent() {
    allFlow.value = sortedRows()
  }

  private fun sortedRows(): List<DynamicFormDraftEntity> =
    rows.values.sortedByDescending { it.createdAtEpochMillis }
}
