package org.armman.sakhi.data.forms

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DynamicFormDraftDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: DynamicFormDraftEntity)

  @Query("SELECT * FROM dynamic_form_drafts WHERE localBeneficiaryId = :localBeneficiaryId")
  suspend fun getByLocalBeneficiaryId(localBeneficiaryId: String): DynamicFormDraftEntity?

  /** Same semantics as `EnrollmentDraftDao.getPendingSync` — never-synced or previously-failed,
   * skipping ones held for duplicate confirmation. */
  @Query(
    "SELECT * FROM dynamic_form_drafts WHERE syncStatus IN ('PENDING', 'FAILED') " +
      "ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getPendingSync(): List<DynamicFormDraftEntity>

  @Query("SELECT * FROM dynamic_form_drafts ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<DynamicFormDraftEntity>

  /** Observable stream of all drafts, newest first. Room re-emits on every insert/update (incl.
   * the sync worker's PENDING→SYNCING→SYNCED transitions), which is what lets the Home upload
   * modal and badge update live while a sync runs — the suspend [getAll] is only a one-shot. */
  @Query("SELECT * FROM dynamic_form_drafts ORDER BY createdAtEpochMillis DESC")
  fun observeAll(): Flow<List<DynamicFormDraftEntity>>
}
