package org.armman.sakhi.data.adhocform

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AdHocFormDraftDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: AdHocFormDraftEntity)

  @Query("SELECT * FROM ad_hoc_form_drafts WHERE localFormInstanceUuid = :localFormInstanceUuid")
  suspend fun getByLocalFormInstanceUuid(localFormInstanceUuid: String): AdHocFormDraftEntity?

  /** Never-synced or previously-failed — same semantics as every other queue's `getPendingSync`. */
  @Query(
    "SELECT * FROM ad_hoc_form_drafts WHERE syncStatus IN ('PENDING', 'FAILED') " +
      "ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getPendingSync(): List<AdHocFormDraftEntity>

  /** Reclaims any draft orphaned in SYNCING by a previous pass that never finished — same
   * rationale as `VisitFormDraftDao.reclaimStaleSyncing`. */
  @Query("UPDATE ad_hoc_form_drafts SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
  suspend fun reclaimStaleSyncing(): Int

  @Query("SELECT * FROM ad_hoc_form_drafts ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<AdHocFormDraftEntity>

  /** Observable stream, newest first — same purpose as the other queues' `observeAll`, for a
   * future Home upload modal to reflect this queue too. */
  @Query("SELECT * FROM ad_hoc_form_drafts ORDER BY createdAtEpochMillis DESC")
  fun observeAll(): Flow<List<AdHocFormDraftEntity>>
}
