package org.armman.sakhi.data.schedule

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * One scheduled visit, generated **on the device** at enrolment / child registration / delivery
 * (SRS FR-S-2.2, FR-S-2.2A) and later uploaded to the server.
 *
 * Unlike the three draft tables, this is not sync *metadata* — it is the real domain record, and
 * the device is its author. The server receives these rows; it does not produce them. Columns
 * therefore mirror the server's `visit_schedules` table
 * (`arogyasakhi-service/apps/visit-form-service/prisma/schema.prisma`) so the bulk upload in
 * CR-023 §5.1 is a near-direct field map.
 *
 * No PII here — only dates, codes and IDs — so unlike [org.armman.sakhi.data.forms.DynamicFormDraftEntity]
 * there is no split into the encrypted store. Everything lives in Room.
 *
 * ### Keys
 * [localScheduleUuid] is the primary key and the **idempotency key for sync**. The server has a
 * matching `local_schedule_uuid` column (added by CR-023 §2) so a replayed upload is recognised
 * rather than duplicated. [serverScheduleId] is null until the row has synced; it is what
 * `POST /visits` must reference when a visit is actually performed.
 */
@Entity(
  tableName = "visit_schedules",
  indices = [
    Index(value = ["localBeneficiaryId", "status"]),
    Index(value = ["scheduledDate"]),
  ],
)
data class VisitScheduleEntity(
  /** Device-generated UUID. PK locally, idempotency key on upload. */
  @PrimaryKey val localScheduleUuid: String,

  /** Server `schedule_id`, null until this row has been uploaded. */
  val serverScheduleId: String? = null,

  /** App-generated beneficiary UUID — the same natural key the draft tables use. */
  val localBeneficiaryId: String,

  /** Server beneficiary UUID, null until the *beneficiary* has synced. A schedule must not be
   * uploaded before this is populated or the server rejects it with an unknown beneficiary. */
  val serverBeneficiaryId: String? = null,

  /** The specific visit as the Sakhi sees it: "ANC3", "PP1", "NN2", "ANC9" (post-EDD). */
  val visitCode: String,

  /** The family this visit belongs to. Must agree with [visitCode]. */
  val visitType: VisitCodeType,

  /** 1-based position within [visitType]. */
  val sequenceNo: Int,

  val scheduledDate: LocalDate,
  val windowStartDate: LocalDate,
  val windowEndDate: LocalDate,

  val anchorType: AnchorType,

  /** The date [scheduledDate] was measured from. Retained for audit and for regeneration. */
  val anchorDate: LocalDate,

  /** For `*_HR` rows: the [localScheduleUuid] of the visit whose *actual* completion triggered
   * this one (SRS FR-S-3.4). Null for every other row. */
  val anchorVisitLocalUuid: String? = null,

  val status: VisitScheduleStatus = VisitScheduleStatus.GENERATED,

  /** Why a row reached its current [status] — currently only [REASON_LAPSED_ON_DELIVERY]. */
  val reasonCode: String? = null,

  /**
   * Which version of the scheduling rules produced this row.
   *
   * Load-bearing, not bookkeeping: it is what lets M3's GoRules migration (CR-032) tell v1 rows
   * from v2 rows and leave already-generated schedules alone, and what a Supervisor-approved
   * LMP/EDD change supersedes against. Never null.
   */
  val generatedByRuleVersion: String,

  val escalationPolicy: EscalationPolicy,

  val createdAtEpochMillis: Long,
)
