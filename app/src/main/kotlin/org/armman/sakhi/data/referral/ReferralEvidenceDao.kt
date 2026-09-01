package org.armman.sakhi.data.referral

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

@Dao
interface ReferralEvidenceDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: ReferralEvidenceMediaEntity)

  @Query("SELECT * FROM referral_evidence_media WHERE referralId = :referralId ORDER BY createdAtEpochMillis ASC")
  suspend fun getByReferralId(referralId: String): List<ReferralEvidenceMediaEntity>

  /** Eligible once EITHER link id is known — [ReferralEvidenceMediaEntity.followupId] (the
   * retired bespoke screen's two-phase capture-then-stamp pipeline; backend-confirmed 2026-08-31
   * the finalize call can only be made once the parent follow-up's real id is known) or
   * [ReferralEvidenceMediaEntity.submissionId] (the ad-hoc form pipeline, always set at insert
   * time — see that field's own doc). A row with neither was captured before its parent
   * submission/follow-up succeeded and isn't eligible yet, regardless of
   * [ReferralEvidenceMediaEntity.syncStatus]. */
  @Query(
    "SELECT * FROM referral_evidence_media " +
      "WHERE syncStatus IN ('PENDING', 'FAILED') AND (followupId IS NOT NULL OR submissionId IS NOT NULL) " +
      "ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getPendingSync(): List<ReferralEvidenceMediaEntity>

  /** Stamps the real follow-up id onto every evidence row captured for this referral before
   * Submit was tapped (see the retired bespoke Referral Follow-up screen's history) —
   * the trigger that makes them eligible for [getPendingSync]. `IS NULL` guard so a retry of an
   * already-stamped row (e.g. re-entering the screen after a partial sync) never overwrites it. */
  @Query(
    "UPDATE referral_evidence_media SET followupId = :followupId " +
      "WHERE referralId = :referralId AND followupId IS NULL",
  )
  suspend fun attachFollowupId(referralId: String, followupId: String)

  /** Same reclaim rationale as every other queue in this app: a row stuck in SYNCING from a run
   * that never finished (process death, WorkManager cancellation) would otherwise be invisible to
   * [getPendingSync] forever. */
  @Query("UPDATE referral_evidence_media SET syncStatus = :pending WHERE syncStatus = :syncing")
  suspend fun reclaimStaleSyncing(
    pending: EnrollmentSyncStatus = EnrollmentSyncStatus.PENDING,
    syncing: EnrollmentSyncStatus = EnrollmentSyncStatus.SYNCING,
  )

  @Query("DELETE FROM referral_evidence_media WHERE localMediaUuid = :localMediaUuid")
  suspend fun deleteByLocalMediaUuid(localMediaUuid: String)
}
