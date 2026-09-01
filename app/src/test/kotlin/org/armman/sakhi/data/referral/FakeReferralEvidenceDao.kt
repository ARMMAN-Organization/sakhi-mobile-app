package org.armman.sakhi.data.referral

import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/** In-memory fake, same conventions as every other `Fake*Dao` in this test source set — unit-
 * testable without a real Room database. */
class FakeReferralEvidenceDao : ReferralEvidenceDao {
  private val rows = mutableMapOf<String, ReferralEvidenceMediaEntity>()

  override suspend fun upsert(entity: ReferralEvidenceMediaEntity) {
    rows[entity.localMediaUuid] = entity
  }

  override suspend fun getByReferralId(referralId: String): List<ReferralEvidenceMediaEntity> =
    rows.values.filter { it.referralId == referralId }.sortedBy { it.createdAtEpochMillis }

  override suspend fun getPendingSync(): List<ReferralEvidenceMediaEntity> =
    rows.values.filter {
      (it.syncStatus == EnrollmentSyncStatus.PENDING || it.syncStatus == EnrollmentSyncStatus.FAILED) &&
        (it.followupId != null || it.submissionId != null)
    }.sortedBy { it.createdAtEpochMillis }

  override suspend fun attachFollowupId(referralId: String, followupId: String) {
    rows.replaceAll { _, entity ->
      if (entity.referralId == referralId && entity.followupId == null) {
        entity.copy(followupId = followupId)
      } else {
        entity
      }
    }
  }

  override suspend fun reclaimStaleSyncing(pending: EnrollmentSyncStatus, syncing: EnrollmentSyncStatus) {
    rows.replaceAll { _, entity -> if (entity.syncStatus == syncing) entity.copy(syncStatus = pending) else entity }
  }

  override suspend fun deleteByLocalMediaUuid(localMediaUuid: String) {
    rows.remove(localMediaUuid)
  }
}
