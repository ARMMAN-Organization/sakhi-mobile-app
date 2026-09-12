package org.armman.sakhi.data.delivery

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryChildRegistrationDraftDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: DeliveryChildRegistrationDraftEntity)

  @Query("SELECT * FROM delivery_child_registration_drafts WHERE localSubmissionUuid = :localSubmissionUuid")
  suspend fun getByLocalSubmissionUuid(localSubmissionUuid: String): DeliveryChildRegistrationDraftEntity?

  /** Never-synced or previously-failed — same semantics as every other queue's `getPendingSync`. */
  @Query(
    "SELECT * FROM delivery_child_registration_drafts WHERE syncStatus IN ('PENDING', 'FAILED') " +
      "ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getPendingSync(): List<DeliveryChildRegistrationDraftEntity>

  /** Reclaims any draft orphaned in SYNCING by a previous pass that never finished — same
   * rationale as every other queue's `reclaimStaleSyncing`. */
  @Query("UPDATE delivery_child_registration_drafts SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
  suspend fun reclaimStaleSyncing(): Int

  @Query("SELECT * FROM delivery_child_registration_drafts ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<DeliveryChildRegistrationDraftEntity>

  /** Observable stream, newest first — feeds the Home "Forms Uploaded" modal via
   * [DeliveryChildRegistrationDraftRepository.observeUploadRecords]. */
  @Query("SELECT * FROM delivery_child_registration_drafts ORDER BY createdAtEpochMillis DESC")
  fun observeAll(): Flow<List<DeliveryChildRegistrationDraftEntity>>
}
