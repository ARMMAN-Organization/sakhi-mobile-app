package org.armman.sakhi.data.adhocform

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/**
 * Room-persisted sync *metadata* for one ad-hoc form submission (Referral / Referral Follow-up /
 * ANC Closure / Child Closure / Beneficiary Reopen) — same split rationale as
 * [org.armman.sakhi.data.visitform.VisitFormDraftEntity]: no PII here, the actual
 * [org.armman.sakhi.data.forms.FormAnswers] payload lives in the encrypted
 * [org.armman.sakhi.data.auth.session.SecureKeyValueStore] instead (see
 * [adHocFormDraftPayloadKey]). Reuses [EnrollmentSyncStatus] rather than declaring a parallel enum,
 * same as every other queue in this app.
 *
 * [localFormInstanceUuid] — NOT [localBeneficiaryId] — is the primary key. This is the deliberate
 * fix over reusing [org.armman.sakhi.data.forms.DynamicFormDraftEntity]'s one-row-per-beneficiary
 * shape: a beneficiary can have several independent ad-hoc drafts over time (e.g. two different
 * Referral Follow-ups months apart, or a Referral and a Reopen both in flight), and keying on
 * beneficiary alone would silently overwrite one draft with another. A fresh UUID is minted per
 * form *opening* (see [org.armman.sakhi.ui.adhocform.AdHocFormViewModel]), so each instance gets
 * its own row.
 */
@Entity(tableName = "ad_hoc_form_drafts")
data class AdHocFormDraftEntity(
  @PrimaryKey val localFormInstanceUuid: String,
  val localBeneficiaryId: String,
  val formCode: String,
  /** The form version the Sakhi actually answered against — sent as-is on sync, not re-resolved
   * against whatever's active by the time the sync runs. Same rationale as every other queue. */
  val formVersionId: String,
  val syncStatus: EnrollmentSyncStatus,
  val createdAtEpochMillis: Long,
  val lastAttemptAtEpochMillis: Long?,
  val retryCount: Int,
  /** Set once `POST /forms/{formCode}/submissions` succeeds. Null until then; terminal once set. */
  val serverSubmissionId: String?,
  val lastErrorMessage: String?,
  /**
   * Referral Follow-up only (`formCode == "REFERRAL_FOLLOWUP_VISIT"`) — the parent referral this
   * submission drives a status transition on, via the paired `POST /referrals/{referralId}/follow-up`
   * call in [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]. Threaded from the
   * profile screen's tap on a referral-incomplete visit (see
   * [org.armman.sakhi.ui.adhocform.AdHocFormViewModel]'s `referralId` nav arg) and persisted here —
   * not just passed transiently through [AdHocFormSubmissionCoordinator.submit] — so a draft that
   * queues offline still carries it when [org.armman.sakhi.data.adhocform.AdHocFormSyncExecutor]
   * retries later. Always null for the other four ad-hoc form codes.
   */
  val referralId: String? = null,
)
