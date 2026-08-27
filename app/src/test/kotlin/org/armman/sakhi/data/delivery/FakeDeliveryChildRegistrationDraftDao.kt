package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/** In-memory [DeliveryChildRegistrationDraftDao], same convention as [FakeDeliverySessionDao] —
 * no Robolectric in this repo, so Room DAOs are exercised through a fake and the real SQL is
 * validated at compile time by Room's annotation processor plus manual QA on the migration. */
class FakeDeliveryChildRegistrationDraftDao : DeliveryChildRegistrationDraftDao {

  private val rows = mutableMapOf<String, DeliveryChildRegistrationDraftEntity>()

  override suspend fun upsert(entity: DeliveryChildRegistrationDraftEntity) {
    rows[entity.localSubmissionUuid] = entity
  }

  override suspend fun getByLocalSubmissionUuid(localSubmissionUuid: String): DeliveryChildRegistrationDraftEntity? =
    rows[localSubmissionUuid]

  override suspend fun getPendingSync(): List<DeliveryChildRegistrationDraftEntity> =
    rows.values
      .filter { it.syncStatus == EnrollmentSyncStatus.PENDING || it.syncStatus == EnrollmentSyncStatus.FAILED }
      .sortedBy { it.createdAtEpochMillis }

  override suspend fun reclaimStaleSyncing(): Int {
    val stale = rows.values.filter { it.syncStatus == EnrollmentSyncStatus.SYNCING }
    stale.forEach { rows[it.localSubmissionUuid] = it.copy(syncStatus = EnrollmentSyncStatus.PENDING) }
    return stale.size
  }

  override suspend fun getAll(): List<DeliveryChildRegistrationDraftEntity> =
    rows.values.sortedByDescending { it.createdAtEpochMillis }
}
