package org.armman.sakhi.data.childregistration

import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/** In-memory fake mirroring `FakeDynamicFormDraftDao`'s conventions, so [ChildFormSyncExecutor]
 * (and any future repository test) is unit-testable without a real Room database. */
class FakeChildFormDraftDao : ChildFormDraftDao {
  private val rows = mutableMapOf<String, ChildFormDraftEntity>()

  override suspend fun upsert(entity: ChildFormDraftEntity) {
    rows[entity.localBeneficiaryId] = entity
  }

  override suspend fun getByLocalBeneficiaryId(localBeneficiaryId: String): ChildFormDraftEntity? =
    rows[localBeneficiaryId]

  override suspend fun getPendingSync(): List<ChildFormDraftEntity> =
    rows.values
      .filter { it.syncStatus == EnrollmentSyncStatus.PENDING || it.syncStatus == EnrollmentSyncStatus.FAILED }
      .sortedBy { it.createdAtEpochMillis }

  override suspend fun getAll(): List<ChildFormDraftEntity> =
    rows.values.sortedByDescending { it.createdAtEpochMillis }
}
