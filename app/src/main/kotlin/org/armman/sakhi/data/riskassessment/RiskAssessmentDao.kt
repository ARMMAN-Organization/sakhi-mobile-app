package org.armman.sakhi.data.riskassessment

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface RiskAssessmentDao {

  /**
   * Replaces [assessment] and every [flags] row for its [RiskAssessmentEntity.localScheduleUuid]
   * in one transaction. Flags are deleted-then-reinserted rather than diffed/upserted individually
   * — a resubmission's flag set is small (one row per graded condition, single digits) and always
   * meant to fully replace the prior set for this visit, so there is no stale-row risk to guard
   * against by keeping the delete+insert atomic with the assessment upsert.
   */
  @Transaction
  suspend fun upsertAssessmentWithFlags(assessment: RiskAssessmentEntity, flags: List<RiskFlagEntity>) {
    upsertAssessment(assessment)
    deleteFlagsForSchedule(assessment.localScheduleUuid)
    if (flags.isNotEmpty()) insertFlags(flags)
  }

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAssessment(entity: RiskAssessmentEntity)

  @Query("DELETE FROM risk_flags WHERE localScheduleUuid = :localScheduleUuid")
  suspend fun deleteFlagsForSchedule(localScheduleUuid: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertFlags(flags: List<RiskFlagEntity>)

  @Query("SELECT * FROM risk_assessments WHERE localScheduleUuid = :localScheduleUuid")
  suspend fun getAssessmentByLocalScheduleUuid(localScheduleUuid: String): RiskAssessmentEntity?

  @Query("SELECT * FROM risk_flags WHERE localScheduleUuid = :localScheduleUuid")
  suspend fun getFlagsByLocalScheduleUuid(localScheduleUuid: String): List<RiskFlagEntity>

  /** Bulk lookup, same "one query per screen load instead of one per visit card" reasoning as
   * [org.armman.sakhi.data.referral.ReferralLinkDao.getByLocalScheduleUuids] — for punch-list
   * item 5 (wiring this cache into the My Beneficiaries / Visit Tracker / Beneficiary Profile risk
   * badges), not used yet by anything in items 1/2. */
  @Query("SELECT * FROM risk_assessments WHERE localScheduleUuid IN (:localScheduleUuids)")
  suspend fun getAssessmentsByLocalScheduleUuids(localScheduleUuids: List<String>): List<RiskAssessmentEntity>
}
