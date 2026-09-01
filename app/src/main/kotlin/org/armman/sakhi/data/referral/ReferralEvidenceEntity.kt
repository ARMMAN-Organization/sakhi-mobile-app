package org.armman.sakhi.data.referral

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus

/**
 * CR-Referral-02: the evidence types a Sakhi can attach to a referral follow-up. Enum names are
 * backend-confirmed verbatim (2026-08-31, `assetType` values on `media-service`) — these are 5 of
 * the service's 12 `assetType` values, not an app-invented set.
 *
 * Built to the task's literal 5-type ask rather than the SRS's own narrower shape — backend's
 * 2026-08-31 read of the FR-S-6.4 vs SRS #21/#23 discrepancy suggests the real requirement count
 * may be 3 fields (case paper+discharge combined, investigation report, a separate FR-S-6.4
 * facility photo), with [REFERRAL_SAKHI_BENEFICIARY_PHOTO] and the case-paper/discharge-summary
 * split not yet SRS-backed. Flagged for product to confirm; the enum accepts whichever types are
 * actually needed, so this is a scope decision, not a technical blocker — see CR-Referral-02's
 * backend-ask doc.
 */
enum class ReferralEvidenceType {
  REFERRAL_HEALTH_FACILITY_PHOTO,
  REFERRAL_SAKHI_BENEFICIARY_PHOTO,
  REFERRAL_CASE_PAPER,
  REFERRAL_DISCHARGE_SUMMARY,
  REFERRAL_INVESTIGATION_REPORT,
}

/**
 * One captured evidence file queued for upload against a referral follow-up. Deliberately one row
 * PER FILE (not per [ReferralEvidenceType]) so a follow-up can carry multiple photos per type
 * (task requirement "multiple media assets per follow-up, not single-file") — [referralId] +
 * [evidenceType] is a one-to-many relationship, not unique.
 *
 * Mirrors [org.armman.sakhi.data.adhocform.AdHocFormDraftEntity]'s split: this table is sync
 * *metadata* only. The captured file itself lives on-disk at [localFilePath] (an app-private
 * `File(context.filesDir, "referral-evidence")` path — see `res/xml/file_paths.xml`'s
 * `referral_evidence_captures` entry and [org.armman.sakhi.ui.referral.ReferralEvidenceCapture]),
 * never in the database.
 *
 * No `AdHocFormDraftEntity`-style encrypted-payload split is needed here — there's no PII in this
 * row beyond the file path, and the file itself is already sandboxed to app-private storage.
 */
@Entity(tableName = "referral_evidence_media")
data class ReferralEvidenceMediaEntity(
  @PrimaryKey val localMediaUuid: String,
  val referralId: String,
  /** [ReferralEvidenceType] stored by enum name — same TEXT-by-name convention as every other
   * enum column in this database. */
  val evidenceType: String,
  /** Absolute path to the captured JPEG in app-private storage (NOT a `content://` URI — that's
   * only used transiently for the camera intent; this is the real file path so a background
   * `CoroutineWorker` can open it without a UI `Context`). */
  val localFilePath: String,
  val syncStatus: EnrollmentSyncStatus,
  val createdAtEpochMillis: Long,
  val lastAttemptAtEpochMillis: Long?,
  val retryCount: Int,
  /**
   * The real follow-up id (`POST /referrals/{referralId}/follow-up`'s returned `followup.id`),
   * stamped onto every pending row for a referral once that submission succeeds (see
   * the retired bespoke Referral Follow-up screen's submit handler). Null from the moment a
   * photo is captured until then.
   *
   * Backend-confirmed 2026-08-31: `POST /media` (the finalize step) accepts an optional
   * `followupId`, but there is genuinely no route to attach media to an already-created
   * follow-up afterwards — so this app's own upload queue must not attempt the finalize call
   * until [followupId] is known. [ReferralEvidenceDao.getPendingSync] enforces this by only
   * returning rows where this column is non-null.
   */
  val followupId: String?,
  /**
   * The ad-hoc-form generic submission id (`POST /forms/REFERRAL_FOLLOWUP_VISIT/submissions`'s
   * returned `data.id`) this evidence file is linked to — the link key for evidence captured via
   * the ad-hoc form's native `case_paper_photo`/`further_investigation_photo` `image` fields
   * (see [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]). Unlike [followupId],
   * this is known synchronously the moment the generic submission succeeds — no two-phase
   * capture-then-stamp dance needed — so a row queued for the ad-hoc pipeline always has this set
   * at insert time, never null. [followupId] and [submissionId] are mutually exclusive in
   * practice: a row has exactly one non-null, depending on which pipeline captured it.
   */
  val submissionId: String? = null,
  /** Set once the real `POST /media` finalize call succeeds — this is `media.id` (the
   * `mediaAssetId`), backend-confirmed 2026-08-31. Null until then; terminal once set. */
  val remoteMediaId: String?,
  val lastErrorMessage: String?,
)
