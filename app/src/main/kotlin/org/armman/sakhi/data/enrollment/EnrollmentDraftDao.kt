package org.armman.sakhi.data.enrollment

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EnrollmentDraftDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: EnrollmentDraftEntity)

  @Query("SELECT * FROM enrollment_drafts WHERE beneficiaryId = :beneficiaryId")
  suspend fun getByBeneficiaryId(beneficiaryId: String): EnrollmentDraftEntity?

  /** Drafts the sync worker (#15) should attempt: never-synced or previously-failed, skipping
   * ones held for duplicate confirmation (those need explicit Sakhi action, not auto-retry). */
  @Query(
    "SELECT * FROM enrollment_drafts WHERE syncStatus IN ('PENDING', 'FAILED') " +
      "ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getPendingSync(): List<EnrollmentDraftEntity>

  @Query("SELECT * FROM enrollment_drafts ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<EnrollmentDraftEntity>
}
