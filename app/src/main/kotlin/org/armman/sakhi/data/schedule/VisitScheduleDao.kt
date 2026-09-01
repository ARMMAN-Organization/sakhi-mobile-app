package org.armman.sakhi.data.schedule

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room access for locally generated visit schedules.
 *
 * Two conventions worth noting, both deliberate:
 *  - **Nothing here deletes.** A schedule that turns out to be wrong is superseded, never removed
 *    (SRS §3A.2.3 — the only regeneration trigger is an approved LMP/EDD change, and the old rows
 *    must survive for audit). There is intentionally no `@Delete` and no `DELETE FROM`.
 *  - **Ordering is always `scheduledDate ASC, sequenceNo ASC`.** `sequenceNo` is the tiebreak for
 *    rows that legitimately share a date — NN2 generated in the same session as the delivery form
 *    (scenario C), or an HR visit landing on a regular visit's date.
 */
@Dao
interface VisitScheduleDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: VisitScheduleEntity)

  /**
   * Writes a whole generated series in one transaction. Generation produces a complete schedule or
   * none at all — a half-written series would show the Sakhi gaps in a woman's care plan, which is
   * worse than showing nothing.
   */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(entities: List<VisitScheduleEntity>)

  @Query("SELECT * FROM visit_schedules WHERE localScheduleUuid = :localScheduleUuid")
  suspend fun getByLocalUuid(localScheduleUuid: String): VisitScheduleEntity?

  @Query(
    "SELECT * FROM visit_schedules WHERE localBeneficiaryId = :localBeneficiaryId " +
      "ORDER BY scheduledDate ASC, sequenceNo ASC",
  )
  suspend fun getForBeneficiary(localBeneficiaryId: String): List<VisitScheduleEntity>

  /**
   * Everything the Sakhi should currently see for one beneficiary — excludes rows retired by a
   * supersession or a delivery lapse. This is what the Beneficiary Profile list renders (CR-022f).
   */
  @Query(
    "SELECT * FROM visit_schedules WHERE localBeneficiaryId = :localBeneficiaryId " +
      "AND status NOT IN ('SUPERSEDED', 'CANCELLED') " +
      "ORDER BY scheduledDate ASC, sequenceNo ASC",
  )
  suspend fun getActiveForBeneficiary(localBeneficiaryId: String): List<VisitScheduleEntity>

  /** Live equivalent of [getActiveForBeneficiary] — Room re-emits on every write, so the profile
   * updates without a manual refresh when a schedule is generated or superseded. */
  @Query(
    "SELECT * FROM visit_schedules WHERE localBeneficiaryId = :localBeneficiaryId " +
      "AND status NOT IN ('SUPERSEDED', 'CANCELLED') " +
      "ORDER BY scheduledDate ASC, sequenceNo ASC",
  )
  fun observeActiveForBeneficiary(localBeneficiaryId: String): Flow<List<VisitScheduleEntity>>

  @Query(
    "SELECT * FROM visit_schedules WHERE status = :status " +
      "ORDER BY scheduledDate ASC, sequenceNo ASC",
  )
  suspend fun getByStatus(status: VisitScheduleStatus): List<VisitScheduleEntity>

  @Query(
    "SELECT * FROM visit_schedules WHERE localBeneficiaryId = :localBeneficiaryId " +
      "AND visitType = :visitType AND status IN ('GENERATED', 'OPEN') " +
      "ORDER BY scheduledDate ASC, sequenceNo ASC",
  )
  suspend fun getOpenByType(
    localBeneficiaryId: String,
    visitType: VisitCodeType,
  ): List<VisitScheduleEntity>

  /**
   * Rows not yet uploaded. Requires a non-null [VisitScheduleEntity.serverBeneficiaryId]: a
   * schedule cannot be uploaded before its beneficiary exists server-side, and including such rows
   * would produce guaranteed-failing requests on every sync pass (see CR-023 §5.1 rule 3).
   */
  @Query(
    "SELECT * FROM visit_schedules WHERE serverScheduleId IS NULL " +
      "AND serverBeneficiaryId IS NOT NULL " +
      "ORDER BY localBeneficiaryId ASC, scheduledDate ASC",
  )
  suspend fun getUnsynced(): List<VisitScheduleEntity>

  @Query("SELECT COUNT(*) FROM visit_schedules WHERE serverScheduleId IS NULL")
  fun observeUnsyncedCount(): Flow<Int>

  /** Records the server ID after a successful upload. Touches nothing else — the schedule content
   * itself is device-authored and must not be rewritten by a sync response. */
  @Query(
    "UPDATE visit_schedules SET serverScheduleId = :serverScheduleId " +
      "WHERE localScheduleUuid = :localScheduleUuid",
  )
  suspend fun markSynced(localScheduleUuid: String, serverScheduleId: String)

  /** Back-fills the server beneficiary ID once the beneficiary itself has synced, which is what
   * makes the beneficiary's schedules eligible for [getUnsynced]. */
  @Query(
    "UPDATE visit_schedules SET serverBeneficiaryId = :serverBeneficiaryId " +
      "WHERE localBeneficiaryId = :localBeneficiaryId",
  )
  suspend fun attachServerBeneficiaryId(localBeneficiaryId: String, serverBeneficiaryId: String)

  @Query(
    "UPDATE visit_schedules SET status = :status, reasonCode = :reasonCode " +
      "WHERE localScheduleUuid = :localScheduleUuid",
  )
  suspend fun updateStatus(
    localScheduleUuid: String,
    status: VisitScheduleStatus,
    reasonCode: String?,
  )

  /**
   * FR-S-3.7 — on delivery form submission every still-open ANC visit lapses, "regardless of
   * status or window position". Completed visits are deliberately excluded: a visit that happened,
   * happened.
   *
   * Recorded as CANCELLED + reason code rather than a LAPSED status, because the server enum has
   * no LAPSED member. See [VisitScheduleStatus] and open question Q3.
   */
  // The reason literal is spelled out rather than interpolated from REASON_LAPSED_ON_DELIVERY:
  // Room parses this string at compile time, so keeping it literal avoids depending on constant
  // folding inside an annotation. VisitScheduleDaoContractTest pins the two together.
  @Query(
    "UPDATE visit_schedules SET status = 'CANCELLED', reasonCode = 'LAPSED_ON_DELIVERY' " +
      "WHERE localBeneficiaryId = :localBeneficiaryId " +
      "AND visitType IN ('ANC', 'ANC_HR', 'ANC_POST_EDD') " +
      "AND status IN ('GENERATED', 'OPEN')",
  )
  suspend fun lapseOpenAncVisits(localBeneficiaryId: String): Int

  /**
   * CR-Closure-01 items #3/#7 — on a mother/child closure submission, every remaining open visit
   * (any [VisitCodeType], not just the ANC family — unlike [lapseOpenAncVisits]) stops being
   * actionable.
   *
   * Client-side sweep, deliberately, not a wait for a server-side cascade to sync down: this DAO's
   * own interface doc ([VisitScheduleRepository]) says nothing pulls a schedule down from the
   * server and overwrites local state — [VisitScheduleApi] only ever uploads. The backend team
   * reported (2026-08-31) building a server-side `POST /visit-schedules/:beneficiaryId/lapse-open`
   * cascade for this, but with no download/sync path in this app to ever observe it, that
   * server-side state — however it works — would never reach a device on its own. This mirrors
   * the original backend-ask's "option (a)" (a client-side sweep, same shape as
   * [lapseOpenAncVisits]) instead.
   *
   * Same CANCELLED-not-LAPSED tradeoff as [lapseOpenAncVisits] (the server enum this row
   * ultimately targets on upload has no LAPSED member either) — a distinct [REASON_LAPSED_ON_CLOSURE]
   * reason code is what actually distinguishes this from a delivery-triggered lapse, same pattern.
   */
  @Query(
    "UPDATE visit_schedules SET status = 'CANCELLED', reasonCode = 'LAPSED_ON_CLOSURE' " +
      "WHERE localBeneficiaryId = :localBeneficiaryId " +
      "AND status IN ('GENERATED', 'OPEN')",
  )
  suspend fun lapseAllOpenVisits(localBeneficiaryId: String): Int

  /**
   * Retires a cohort after an approved LMP/EDD change. GENERATED and OPEN only — COMPLETED rows are
   * never touched, and nothing is deleted.
   */
  @Query(
    "UPDATE visit_schedules SET status = 'SUPERSEDED' " +
      "WHERE localBeneficiaryId = :localBeneficiaryId " +
      "AND status IN ('GENERATED', 'OPEN')",
  )
  suspend fun supersedeOpenVisits(localBeneficiaryId: String): Int

  @Query("SELECT COUNT(*) FROM visit_schedules WHERE localBeneficiaryId = :localBeneficiaryId")
  suspend fun countForBeneficiary(localBeneficiaryId: String): Int

  /**
   * Counts rows of one family, **including superseded and cancelled ones**.
   *
   * This is the idempotency guard for generation, so it must see retired rows too: a mother whose
   * ANC schedule was lapsed at delivery has still had ANC generated, and re-running the trigger
   * must not produce a second series alongside the lapsed one.
   */
  @Query(
    "SELECT COUNT(*) FROM visit_schedules " +
      "WHERE localBeneficiaryId = :localBeneficiaryId AND visitType = :visitType",
  )
  suspend fun countForBeneficiaryAndType(
    localBeneficiaryId: String,
    visitType: VisitCodeType,
  ): Int
}
