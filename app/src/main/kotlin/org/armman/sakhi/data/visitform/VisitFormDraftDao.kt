package org.armman.sakhi.data.visitform

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitFormDraftDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: VisitFormDraftEntity)

  @Query("SELECT * FROM visit_form_drafts WHERE localScheduleUuid = :localScheduleUuid")
  suspend fun getByLocalScheduleUuid(localScheduleUuid: String): VisitFormDraftEntity?

  /** Bulk lookup for [org.armman.sakhi.data.beneficiaryprofile.ScheduleBackedBeneficiaryProfileRepository]
   * — one query per profile load instead of one per visit card, same pattern as
   * [org.armman.sakhi.data.referral.ReferralLinkDao.getByLocalScheduleUuids]. */
  @Query("SELECT * FROM visit_form_drafts WHERE localScheduleUuid IN (:localScheduleUuids)")
  suspend fun getByLocalScheduleUuids(localScheduleUuids: List<String>): List<VisitFormDraftEntity>

  /** Same semantics as `DynamicFormDraftDao.getPendingSync` — never-synced or previously-failed
   * (including one that got as far as [VisitFormDraftEntity.serverVisitId] but no further). */
  @Query(
    "SELECT * FROM visit_form_drafts WHERE syncStatus IN ('PENDING', 'FAILED') " +
      "ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getPendingSync(): List<VisitFormDraftEntity>

  /** Reclaims any draft orphaned in SYNCING by a previous pass that never finished — same
   * rationale as `DynamicFormDraftDao.reclaimStaleSyncing`: re-attempting is safe, since a
   * step-1-succeeded draft resumes from [VisitFormDraftEntity.serverVisitId] rather than
   * re-creating the visit instance. */
  @Query("UPDATE visit_form_drafts SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
  suspend fun reclaimStaleSyncing(): Int

  /** Rows whose main submission already succeeded but the best-effort risk-assessment/referral-
   * creation step (see [VisitFormSubmissionCoordinator.triggerRiskAssessment]) never completed —
   * see [VisitFormDraftEntity.riskAssessmentStatus]'s doc. Retried independently of
   * [getPendingSync] by [VisitFormSyncExecutor.retryRiskAssessments] on every sync pass (manual
   * Data Upload and background alike), since these rows are already SYNCED and would never
   * otherwise be looked at again. */
  @Query(
    "SELECT * FROM visit_form_drafts WHERE syncStatus = 'SYNCED' " +
      "AND riskAssessmentStatus IN ('PENDING', 'FAILED') ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getRiskAssessmentPending(): List<VisitFormDraftEntity>

  @Query("SELECT * FROM visit_form_drafts ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<VisitFormDraftEntity>

  /** Observable stream, newest first — same purpose as `DynamicFormDraftDao.observeAll`: lets the
   * Home upload modal/badge reflect this queue live too. */
  @Query("SELECT * FROM visit_form_drafts ORDER BY createdAtEpochMillis DESC")
  fun observeAll(): Flow<List<VisitFormDraftEntity>>
}
