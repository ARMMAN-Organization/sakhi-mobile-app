package org.armman.sakhi.data.childregistration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

  /** Returns drafts orphaned in SYNCING to PENDING so a later run can claim them — see
   * [org.armman.sakhi.data.forms.DynamicFormDraftDao.reclaimStaleSyncing] for the full rationale and
   * why re-attempting is safe. */
  @Query("UPDATE child_registration_drafts SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
  suspend fun reclaimStaleSyncing(): Int

  @Query("SELECT * FROM child_registration_drafts ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<ChildFormDraftEntity>

  /** Observable [getAll] — Room re-emits on every insert/update, which is what lets the Home
   * upload modal show this queue's progress live during a manual sync run. */
  @Query("SELECT * FROM child_registration_drafts ORDER BY createdAtEpochMillis DESC")
  fun observeAll(): Flow<List<ChildFormDraftEntity>>
}
