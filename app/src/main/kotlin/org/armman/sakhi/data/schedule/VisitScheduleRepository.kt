package org.armman.sakhi.data.schedule

import kotlinx.coroutines.flow.Flow

/**
 * Local store for generated visit schedules.
 *
 * Read-heavy by design: the device is the author of these rows, so there is no "fetch from server"
 * method here. The sync executor (CR-022e) pushes upward and writes back server IDs; nothing pulls
 * a schedule down and overwrites local state.
 *
 * No delete operation exists anywhere in this interface — see [VisitScheduleDao] for why.
 */
interface VisitScheduleRepository {

  /** Persists a freshly generated series. All-or-nothing. */
  suspend fun saveGenerated(schedules: List<VisitScheduleEntity>)

  /** Every row for a beneficiary, including superseded and cancelled. Audit and debugging. */
  suspend fun getForBeneficiary(localBeneficiaryId: String): List<VisitScheduleEntity>

  /** What the Sakhi should currently see — excludes superseded and cancelled rows. */
  suspend fun getActiveForBeneficiary(localBeneficiaryId: String): List<VisitScheduleEntity>

  /** Live version of [getActiveForBeneficiary]; re-emits on generation and supersession. */
  fun observeActiveForBeneficiary(localBeneficiaryId: String): Flow<List<VisitScheduleEntity>>

  /** Open visits of one family, oldest first. Used to find HR trigger targets and lapse candidates. */
  suspend fun getOpenByType(
    localBeneficiaryId: String,
    visitType: VisitCodeType,
  ): List<VisitScheduleEntity>

  /** True when a beneficiary already has any schedule at all. */
  suspend fun hasSchedule(localBeneficiaryId: String): Boolean

  /**
   * True when this family has already been generated — the per-family idempotency guard.
   *
   * Per-family rather than global because one beneficiary accumulates families over time: a mother
   * gets ANC at enrolment and PP at delivery, so a global check would block the second. Counts
   * retired rows too, so a lapsed ANC series still reads as "already generated".
   */
  suspend fun hasScheduleOfType(localBeneficiaryId: String, visitType: VisitCodeType): Boolean

  /** Rows awaiting upload whose beneficiary has already synced. */
  suspend fun getUnsynced(): List<VisitScheduleEntity>

  fun observeUnsyncedCount(): Flow<Int>

  suspend fun markSynced(localScheduleUuid: String, serverScheduleId: String)

  /** Makes a beneficiary's schedules upload-eligible once the beneficiary itself has synced. */
  suspend fun attachServerBeneficiaryId(localBeneficiaryId: String, serverBeneficiaryId: String)

  suspend fun updateStatus(
    localScheduleUuid: String,
    status: VisitScheduleStatus,
    reasonCode: String? = null,
  )

  /** FR-S-3.7 — lapses every open ANC-family visit on delivery. Returns the number affected. */
  suspend fun lapseOpenAncVisits(localBeneficiaryId: String): Int

  /** Retires the open cohort ahead of a regeneration. Completed rows are untouched. */
  suspend fun supersedeOpenVisits(localBeneficiaryId: String): Int
}
