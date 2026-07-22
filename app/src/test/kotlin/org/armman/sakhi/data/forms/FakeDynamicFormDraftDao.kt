package org.armman.sakhi.data.forms

import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/** In-memory fake mirroring `FakeEnrollmentDraftDao`'s conventions, so [RoomDynamicFormDraftRepository]
 * and [DynamicFormSyncExecutor] are unit-testable without a real Room database. */
class FakeDynamicFormDraftDao : DynamicFormDraftDao {
  private val rows = mutableMapOf<String, DynamicFormDraftEntity>()

  override suspend fun upsert(entity: DynamicFormDraftEntity) {
    rows[entity.localBeneficiaryId] = entity
  }

  override suspend fun getByLocalBeneficiaryId(localBeneficiaryId: String): DynamicFormDraftEntity? =
    rows[localBeneficiaryId]

  override suspend fun getPendingSync(): List<DynamicFormDraftEntity> =
    rows.values
      .filter { it.syncStatus == EnrollmentSyncStatus.PENDING || it.syncStatus == EnrollmentSyncStatus.FAILED }
      .sortedBy { it.createdAtEpochMillis }

  override suspend fun getAll(): List<DynamicFormDraftEntity> =
    rows.values.sortedByDescending { it.createdAtEpochMillis }
}
