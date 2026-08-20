package org.armman.sakhi.data.delivery

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/**
 * Room-persisted sync *metadata* for one `DELIVERY_VISIT` form submission (CR-042) — same split
 * rationale as [org.armman.sakhi.data.adhocform.AdHocFormDraftEntity]: no PII here, the actual
 * [org.armman.sakhi.data.forms.FormAnswers] payload lives in the encrypted
 * [org.armman.sakhi.data.auth.session.SecureKeyValueStore] instead (see
 * [deliveryFormDraftPayloadKey]). Reuses [EnrollmentSyncStatus], same as every other queue.
 *
 * Kept as its own table rather than reusing [org.armman.sakhi.data.adhocform.AdHocFormDraftEntity]
 * even though the shape is nearly identical: a `DELIVERY_VISIT` submission has a side effect no
 * other ad-hoc form has ([DeliverySessionRepository] must be updated and
 * [org.armman.sakhi.data.schedule.VisitScheduleCoordinator.onDeliveryRecorded] must run, both only
 * once, on the specific submission that actually succeeds — whether that's the immediate online
 * attempt or a later background retry). Routing delivery through the shared ad-hoc table would mean
 * teaching that generic queue's sync executor about one form code's special side effect; a
 * dedicated table keeps that logic entirely inside [DeliveryFormSubmissionCoordinator] instead.
 *
 * [localSessionUuid] links this draft back to its [DeliverySessionEntity] row (written at the same
 * time, before any network attempt — see [DeliveryFormSubmissionCoordinator.submit]) so a resumed
 * sync can find the right session to update on success.
 */
@Entity(tableName = "delivery_form_drafts")
data class DeliveryFormDraftEntity(
  @PrimaryKey val localSubmissionUuid: String,
  val localBeneficiaryId: String,
  val localSessionUuid: String,
  val formVersionId: String,
  val syncStatus: EnrollmentSyncStatus,
  val createdAtEpochMillis: Long,
  val lastAttemptAtEpochMillis: Long?,
  val retryCount: Int,
  /** Set once `POST /forms/DELIVERY_VISIT/submissions` succeeds. Null until then; terminal once
   * set. */
  val serverSubmissionId: String?,
  val lastErrorMessage: String?,
)
