package org.armman.sakhi.data.forms

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/**
 * Room-persisted sync *metadata* for one dynamic-form draft — the CR-018 twin of
 * [org.armman.sakhi.data.enrollment.EnrollmentDraftEntity], same split rationale: no PII here,
 * the actual [FormAnswers] payload lives in the encrypted
 * [org.armman.sakhi.data.auth.session.SecureKeyValueStore] instead (see
 * [DynamicFormDraftPayloadKey]). Reuses [EnrollmentSyncStatus] rather than declaring a parallel
 * enum — the sync lifecycle (pending/syncing/synced/duplicate/failed) is identical in meaning.
 *
 * [localBeneficiaryId] is the app-generated UUID from
 * [org.armman.sakhi.ui.forms.DynamicMotherRegistrationViewModel.beneficiaryId] — the natural key,
 * same role [org.armman.sakhi.data.enrollment.EnrollmentRecord.beneficiaryId] plays for the static
 * flow.
 */
@Entity(tableName = "dynamic_form_drafts")
data class DynamicFormDraftEntity(
  @PrimaryKey val localBeneficiaryId: String,
  val formCode: String,
  /** The form version the Sakhi actually answered against — sent as-is on sync, not re-resolved
   * against whatever's active by the time the sync runs. See [DynamicFormSubmissionCoordinator]. */
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
