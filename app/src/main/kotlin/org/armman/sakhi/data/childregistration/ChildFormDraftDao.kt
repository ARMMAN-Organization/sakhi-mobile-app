package org.armman.sakhi.data.childregistration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room DAO for Children Register drafts (CR-020) — same query surface as
 * [org.armman.sakhi.data.forms.DynamicFormDraftDao], against the dedicated
 * `child_registration_drafts` table. */
@Dao
interface ChildFormDraftDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: ChildFormDraftEntity)

  @Query("SELECT * FROM child_registration_drafts WHERE localBeneficiaryId = :localBeneficiaryId")
  suspend fun getByLocalBeneficiaryId(localBeneficiaryId: String): ChildFormDraftEntity?

  /** Same semantics as `DynamicFormDraftDao.getPendingSync` — never-synced or previously-failed,
   * skipping ones held for duplicate confirmation. */
  @Query(
    "SELECT * FROM child_registration_drafts WHERE syncStatus IN ('PENDING', 'FAILED') " +
      "ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getPendingSync(): List<ChildFormDraftEntity>

  @Query("SELECT * FROM child_registration_drafts ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<ChildFormDraftEntity>
}
