package org.armman.sakhi.data.forms

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
}
