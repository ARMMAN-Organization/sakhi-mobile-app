package org.armman.sakhi.data.delivery

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryFormDraftDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: DeliveryFormDraftEntity)

  @Query("SELECT * FROM delivery_form_drafts WHERE localSubmissionUuid = :localSubmissionUuid")
  suspend fun getByLocalSubmissionUuid(localSubmissionUuid: String): DeliveryFormDraftEntity?

  /** Never-synced or previously-failed — same semantics as every other queue's `getPendingSync`. */
  @Query(
    "SELECT * FROM delivery_form_drafts WHERE syncStatus IN ('PENDING', 'FAILED') " +
      "ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getPendingSync(): List<DeliveryFormDraftEntity>

  /** Reclaims any draft orphaned in SYNCING by a previous pass that never finished — same
   * rationale as every other queue's `reclaimStaleSyncing`. */
  @Query("UPDATE delivery_form_drafts SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
  suspend fun reclaimStaleSyncing(): Int

  @Query("SELECT * FROM delivery_form_drafts ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<DeliveryFormDraftEntity>

  /** Observable stream, newest first — feeds the Home "Forms Uploaded" modal via
   * [DeliveryFormDraftRepository.observeUploadRecords] (bharath, 2026-09-11: the Delivery queue
   * had no card in that modal at all before this). */
  @Query("SELECT * FROM delivery_form_drafts ORDER BY createdAtEpochMillis DESC")
  fun observeAll(): Flow<List<DeliveryFormDraftEntity>>
}
