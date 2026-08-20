package org.armman.sakhi.data.delivery

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeliverySessionDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: DeliverySessionEntity)

  @Query("SELECT * FROM delivery_sessions WHERE localSessionUuid = :localSessionUuid")
  suspend fun getBySessionUuid(localSessionUuid: String): DeliverySessionEntity?

  /**
   * The one session a beneficiary profile resumes into, if any. Excludes [DeliverySessionStep.DONE]
   * so a completed session never resurfaces as "still in progress" — matches
   * [BeneficiaryProfileViewModel]'s `hasDeliveryRecorded` gating, which is the other place this
   * "has delivery already happened" question gets asked, via a different signal
   * ([org.armman.sakhi.data.schedule.VisitScheduleRepository.hasScheduleOfType]).
   *
   * `LIMIT 1` is a defensive belt, not a designed multiplicity — the repository layer is
   * responsible for never starting a second concurrent session for the same beneficiary; this
   * query just never lets a query-level bug surface two.
   */
  @Query(
    "SELECT * FROM delivery_sessions WHERE localBeneficiaryId = :localBeneficiaryId " +
      "AND step != 'DONE' LIMIT 1",
  )
  suspend fun getActiveForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity?

  /**
   * The beneficiary's own delivery session regardless of [DeliverySessionStep] — unlike
   * [getActiveForBeneficiary], this does NOT exclude [DeliverySessionStep.DONE]. Added for CR-042's
   * `NEONATAL_VISIT` prefill ([org.armman.sakhi.data.delivery.DeliveryToNeonatalPrefill]'s own
   * doc): NN2 can be opened off the regular tracker long after the session that generated it has
   * already finished, and it still needs to resolve back to the same
   * [DeliverySessionEntity.deliverySubmissionLocalUuid] to read the original `DELIVERY_VISIT`
   * answers. `LIMIT 1` is the same defensive belt as [getActiveForBeneficiary]'s — a beneficiary
   * has at most one delivery session, ever (see [DeliverySessionEntity]'s own doc) — not a designed
   * multiplicity this query relies on.
   */
  @Query(
    "SELECT * FROM delivery_sessions WHERE localBeneficiaryId = :localBeneficiaryId " +
      "ORDER BY createdAtEpochMillis DESC LIMIT 1",
  )
  suspend fun getMostRecentForBeneficiary(localBeneficiaryId: String): DeliverySessionEntity?

  /** Audit/debugging only — mirrors every other queue table's `getAll`. */
  @Query("SELECT * FROM delivery_sessions ORDER BY createdAtEpochMillis DESC")
  suspend fun getAll(): List<DeliverySessionEntity>
}
