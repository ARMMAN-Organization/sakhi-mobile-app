package org.armman.sakhi.data.enrollment

/** In-memory fake so [RoomEnrollmentRepository] and [EnrollmentSyncExecutor] are unit-testable
 * without a real Room database (this repo has no Robolectric/instrumented test setup, so the
 * generated Room implementation itself is exercised only manually/on-device). */
class FakeEnrollmentDraftDao : EnrollmentDraftDao {
  private val rows = mutableMapOf<String, EnrollmentDraftEntity>()

  override suspend fun upsert(entity: EnrollmentDraftEntity) {
    rows[entity.beneficiaryId] = entity
  }

  override suspend fun getByBeneficiaryId(beneficiaryId: String): EnrollmentDraftEntity? =
    rows[beneficiaryId]

  override suspend fun getPendingSync(): List<EnrollmentDraftEntity> =
    rows.values
      .filter { it.syncStatus == EnrollmentSyncStatus.PENDING || it.syncStatus == EnrollmentSyncStatus.FAILED }
      .sortedBy { it.createdAtEpochMillis }

  override suspend fun getAll(): List<EnrollmentDraftEntity> =
    rows.values.sortedByDescending { it.createdAtEpochMillis }
}
