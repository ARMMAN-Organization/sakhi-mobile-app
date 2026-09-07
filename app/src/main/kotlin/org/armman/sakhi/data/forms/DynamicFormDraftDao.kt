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

  /** CR-Registration-Edit: the Beneficiary Profile only ever knows the server-assigned
   * beneficiaryId, never the local draft's own [DynamicFormDraftEntity.localBeneficiaryId] — this
   * is how the Edit stub finds its way back to the draft row (and the [DynamicFormDraftEntity
   * .remoteSubmissionId] on it) for a beneficiary that has already synced. Null for anyone not yet
   * synced, exactly like [DynamicFormDraftEntity.remoteBeneficiaryId] itself. */
  @Query("SELECT * FROM dynamic_form_drafts WHERE remoteBeneficiaryId = :remoteBeneficiaryId")
  suspend fun getByRemoteBeneficiaryId(remoteBeneficiaryId: String): DynamicFormDraftEntity?

  /** Same semantics as `EnrollmentDraftDao.getPendingSync` — never-synced or previously-failed,
   * skipping ones held for duplicate confirmation. */
  @Query(
    "SELECT * FROM dynamic_form_drafts WHERE syncStatus IN ('PENDING', 'FAILED') " +
      "ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getPendingSync(): List<DynamicFormDraftEntity>

  /**
   * Returns any draft still marked SYNCING to PENDING so a later run can claim it.
   *
   * SYNCING is written *before* the network attempt, but [getPendingSync] only selects
   * PENDING/FAILED. So if the process dies mid-attempt — app killed, the OS stops the worker, or
   * WorkManager cancels the job because a fresh manual tap replaced it — the row stays SYNCING
   * forever and **no future sync will ever pick it up**, while still counting toward the Home badge.
   * That's a draft the Sakhi can see but can never upload.
   *
   * Called at the start of a sync pass, when nothing of ours is genuinely in flight, so this can
   * only ever reclaim orphans. Re-attempting one is safe: both API calls are idempotent on
   * `localCaseUuid` / `localSubmissionUuid`, so a row that did reach the server is recognised as a
   * replay rather than duplicated.
   */
  @Query("UPDATE dynamic_form_drafts SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
  suspend fun reclaimStaleSyncing(): Int

  @Query("SELECT * FROM dynamic_form_drafts ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<DynamicFormDraftEntity>

  /** Observable stream of all drafts, newest first. Room re-emits on every insert/update (incl.
   * the sync worker's PENDING→SYNCING→SYNCED transitions), which is what lets the Home upload
   * modal and badge update live while a sync runs — the suspend [getAll] is only a one-shot. */
  @Query("SELECT * FROM dynamic_form_drafts ORDER BY createdAtEpochMillis DESC")
  fun observeAll(): Flow<List<DynamicFormDraftEntity>>
}
