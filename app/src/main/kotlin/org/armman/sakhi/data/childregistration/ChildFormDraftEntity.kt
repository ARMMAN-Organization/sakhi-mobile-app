package org.armman.sakhi.data.childregistration

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/**
 * Room-persisted sync *metadata* for one Children Register draft (CR-020) — the standalone twin of
 * [org.armman.sakhi.data.forms.DynamicFormDraftEntity], deliberately a separate table
 * (`child_registration_drafts`) so the child flow never shares queue rows with the mother flow and
 * can be reasoned about / migrated independently. Same split rationale as the mother twin: no PII
 * here, the actual [org.armman.sakhi.data.forms.FormAnswers] payload lives in the encrypted
 * [org.armman.sakhi.data.auth.session.SecureKeyValueStore] instead (see [ChildFormDraftPayloadKey]).
 * Reuses [EnrollmentSyncStatus] rather than declaring a parallel enum — the sync lifecycle
 * (pending/syncing/synced/duplicate/failed) is identical in meaning.
 *
 * [localBeneficiaryId] is the app-generated UUID minted once per draft by
 * [org.armman.sakhi.ui.childregistration.DynamicChildRegistrationViewModel] — the natural key and
 * the `localCaseUuid` sent to `POST /beneficiaries`.
 */
@Entity(tableName = "child_registration_drafts")
data class ChildFormDraftEntity(
  @PrimaryKey val localBeneficiaryId: String,
  val formCode: String,
  /** The form version the Sakhi actually answered against — sent as-is on sync, not re-resolved
   * against whatever's active by the time the sync runs. See [ChildRegistrationSubmissionCoordinator]. */
  val formVersionId: String,
  val localSubmissionUuid: String,
  val syncStatus: EnrollmentSyncStatus,
  val createdAtEpochMillis: Long,
  val lastAttemptAtEpochMillis: Long?,
  val retryCount: Int,
  val remoteBeneficiaryId: String?,
  val remoteSubmissionId: String?,
  val lastErrorMessage: String?,
)
