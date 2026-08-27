package org.armman.sakhi.data.delivery

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/**
 * Room-persisted sync *metadata* for one CR-042 `CHILD_REGISTRATION` submission against a child
 * already auto-created by a `DELIVERY_VISIT` submission — the delivery-session twin of
 * [org.armman.sakhi.data.childregistration.ChildFormDraftEntity], same split rationale as every
 * other queue in this app: no PII here, the actual [org.armman.sakhi.data.forms.FormAnswers]
 * payload lives in the encrypted [org.armman.sakhi.data.auth.session.SecureKeyValueStore] instead
 * (see [DeliveryChildRegistrationDraftPayloadKey]).
 *
 * Kept as its own table rather than reusing [org.armman.sakhi.data.childregistration
 * .ChildFormDraftEntity] even though the form code is identical, for the same reason
 * [DeliveryFormDraftEntity] is its own table rather than reusing
 * [org.armman.sakhi.data.adhocform.AdHocFormDraftEntity]: this submission has a CR-042-specific
 * side effect no standalone Children Register draft has ([DeliverySessionRepository] must advance
 * — see [DeliveryChildRegistrationSubmissionCoordinator] — exactly once, on whichever attempt
 * actually succeeds). It ALSO cannot reuse [ChildFormDraftEntity] for a more basic reason: that
 * table's `localBeneficiaryId` primary key IS the `localCaseUuid` sent to `POST /beneficiaries`
 * (see that entity's own doc) — a call this flow never makes, since the child beneficiary already
 * exists. [serverBeneficiaryId] below is the already-known real id, not a local-only placeholder.
 *
 * Keyed by [localSubmissionUuid] (not [serverBeneficiaryId]) so a retry of the exact same attempt
 * is idempotent the same way every other queue's row is, and so twins (two children from one
 * delivery) get two independent rows rather than colliding on one primary key.
 */
@Entity(tableName = "delivery_child_registration_drafts")
data class DeliveryChildRegistrationDraftEntity(
  @PrimaryKey val localSubmissionUuid: String,
  /** Links this draft back to its [DeliverySessionEntity] row so
   * [DeliveryChildRegistrationSubmissionCoordinator.submit] can advance the right session on
   * success, whether that's the immediate online attempt or a later background retry. */
  val localSessionUuid: String,
  /** The child's already-known server beneficiary id — see this entity's own doc for why this is
   * never a locally-minted placeholder. */
  val serverBeneficiaryId: String,
  /** The form version the Sakhi actually answered against — sent as-is on sync, not re-resolved
   * against whatever's active by the time the sync runs. Same convention as
   * [DeliveryFormDraftEntity.formVersionId]. */
  val formVersionId: String,
  val syncStatus: EnrollmentSyncStatus,
  val createdAtEpochMillis: Long,
  val lastAttemptAtEpochMillis: Long?,
  val retryCount: Int,
  val lastErrorMessage: String?,
)
