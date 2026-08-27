package org.armman.sakhi.data.delivery

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * Room-persisted progress for one Delivery Event Session (CR-042: Delivery -> auto child
 * registration -> PP1 -> NN1/NN2). Exists so the session survives the app being killed
 * mid-sequence — e.g. delivery recorded, one of twins registered, app dies before the second
 * child or PP1 — without re-submitting the delivery form or losing which child still needs
 * registering.
 *
 * ### Why three nullable child columns, not a list
 * Mirrors the backend's own `DELIVERY_VISIT` schema, which flattens `child1_*` / `child2_*` /
 * `child3_*`
 * fields rather than a repeating group (confirmed 2026-08-18 handoff) — up to three children per
 * delivery is the real, bounded cap this whole feature works against, so three plain nullable
 * columns match the domain exactly and need no new [androidx.room.TypeConverter] the way a
 * `List<String>` column would.
 *
 * ### Why this is keyed by a fresh [localSessionUuid], not [localBeneficiaryId]
 * A mother can only ever have one delivery (this table has no history requirement — CR-042 does
 * not cover a second pregnancy's delivery, which is a distinct beneficiary case per SR-2.4/2.5),
 * but keying on a fresh id rather than the beneficiary id directly mirrors every other queue table
 * in this database ([org.armman.sakhi.data.adhocform.AdHocFormDraftEntity] etc.) and leaves room
 * for a future audit trail without a schema change if that need ever arises.
 *
 * ### Not a sync queue
 * Unlike every other table in [org.armman.sakhi.data.db.SakhiDatabase], this table has no
 * `syncStatus` — it does not itself get uploaded. It only tracks *which step of the local session*
 * the Sakhi is on; each step's own form (DELIVERY_VISIT / CHILD_REGISTRATION / POSTPARTUM_VISIT /
 * NEONATAL_VISIT) is queued for sync through its own existing draft table exactly as it is outside
 * this session. This row is purely a resume pointer.
 */
@Entity(tableName = "delivery_sessions")
data class DeliverySessionEntity(
  @PrimaryKey val localSessionUuid: String,
  /** The mother's local beneficiary id — this session's anchor. */
  val localBeneficiaryId: String,
  val step: DeliverySessionStep,
  /**
   * The `DELIVERY_VISIT` submission's own `localSubmissionUuid`, set the moment that form
   * succeeds. Carried here (rather than re-derived) so a resumed session calls
   * [org.armman.sakhi.data.schedule.VisitScheduleCoordinator.onDeliveryRecorded] and the
   * downstream child/PP/NN steps against the exact same submission it already recorded — not a
   * newly-minted one, which is what would make a resume look like a second, separate delivery.
   * Null only while [step] is still [DeliverySessionStep.DELIVERY_FORM].
   */
  val deliverySubmissionLocalUuid: String?,
  /**
   * The date the `DELIVERY_VISIT` form itself was filled — an answer on that form, distinct from
   * the delivery date, and the same value [DeliveryFormSubmissionCoordinator.submit] already
   * resolves and passes to [org.armman.sakhi.data.schedule.ScheduleContext.deliveryFormFilledOn].
   * Carried here (set once, at the same moment as [deliverySubmissionLocalUuid], and never revised
   * afterwards) so that once this session reaches [DeliverySessionStep.PP1],
   * [org.armman.sakhi.data.schedule.sameSessionNnVisit] can be asked — without re-deriving
   * anything — whether a same-session NN visit exists at all before deciding whether the session's
   * next step is [DeliverySessionStep.NN] or [DeliverySessionStep.DONE].
   *
   * Null only while [step] is still [DeliverySessionStep.DELIVERY_FORM] — same nullability
   * contract as [deliverySubmissionLocalUuid].
   */
  val deliveryFormFilledOn: LocalDate? = null,
  val child1BeneficiaryId: String? = null,
  val child2BeneficiaryId: String? = null,
  val child3BeneficiaryId: String? = null,
  /**
   * 0-based index into (child1, child2, child3) for whichever child still needs registering.
   * Meaningful only while [step] is [DeliverySessionStep.CHILD_REGISTRATION]; a twin/triplet
   * sequence resumes at this index rather than restarting from child1.
   */
  val nextChildIndexToRegister: Int = 0,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)
