package org.armman.sakhi.data.visitform

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/**
 * Room-persisted sync *metadata* for one visit-form submission — the CR-026b twin of
 * [org.armman.sakhi.data.forms.DynamicFormDraftEntity], same split rationale: no PII here, the
 * actual [org.armman.sakhi.data.forms.FormAnswers] payload lives in the encrypted
 * [org.armman.sakhi.data.auth.session.SecureKeyValueStore] instead (see
 * [visitFormDraftPayloadKey]). Reuses [EnrollmentSyncStatus] rather than declaring a parallel
 * enum — DUPLICATE_CONFLICT is unused here (visit submissions have no duplicate-detection
 * concept), everything else means the same thing it does for the other three queues.
 *
 * [localScheduleUuid] is the natural key — it's already how [VisitFormSubmissionCoordinator]
 * looks up the schedule row, and a beneficiary can have many visits, so (unlike the other three
 * queues, keyed by beneficiary) the visit itself has to be the key here.
 */
@Entity(tableName = "visit_form_drafts")
data class VisitFormDraftEntity(
  @PrimaryKey val localScheduleUuid: String,
  val formCode: String,
  /** The form version the Sakhi actually answered against — sent as-is on sync, not re-resolved
   * against whatever's active by the time the sync runs. Same rationale as the other queues. */
  val formVersionId: String,
  /**
   * Minted once — the moment this draft is first saved (see [RoomVisitFormDraftRepository]'s
   * `saveLocally`) — and held for the draft's lifetime, same once-per-draft/stable-across-retries
   * contract as [org.armman.sakhi.data.forms.DynamicFormDraftEntity.localSubmissionUuid]. Sent
   * as-is to `POST /forms/:formCode/submissions` on every attempt, including retries, via
   * [VisitFormSubmissionCoordinator.submit]'s `localSubmissionUuid` param — so a resumed sync
   * after a step-2-only failure replays the *same* idempotency key instead of minting a fresh
   * one. Before this field existed, the coordinator generated a new uuid on every call, so a
   * retry after an ambiguous network failure (the request may have already reached the server)
   * risked creating a duplicate `form_submissions` row server-side.
   */
  val localSubmissionUuid: String,
  val visitDateIso: String,
  val syncStatus: EnrollmentSyncStatus,
  val createdAtEpochMillis: Long,
  val lastAttemptAtEpochMillis: Long?,
  val retryCount: Int,
  /**
   * Set the moment `POST /visits` succeeds, before the form-submission call is even attempted —
   * see [VisitFormSubmissionCoordinator.submit]'s `onVisitCreated` param. A retry that finds this
   * already set skips straight to step 2 instead of creating a second visit instance for the same
   * visit. Null until step 1 succeeds; terminal once set (never cleared).
   */
  val serverVisitId: String?,
  val lastErrorMessage: String?,
)
