package org.armman.sakhi.data.lmpchange

import java.io.File
import java.time.LocalDate

private const val SUPERVISOR_STATUS_PENDING = "PENDING"
private const val SUPERVISOR_STATUS_APPROVED = "APPROVED"
private const val SUPERVISOR_STATUS_REJECTED = "REJECTED"

/**
 * LMP/EDD correction request data boundary (tasks 2/3/4, LMP/Reopen/Referral/Audit task list).
 * Confirmed contract (2026-09-02): `POST /lmp-change-requests` (role SAKHI), `GET
 * /lmp-change-requests?beneficiaryId=` (roles SAKHI, SUPERVISOR, MANAGER) — see the backend
 * paste-back captured in "CR-LMP-Reopen-Referral-Audit Backend Contract Answers and Next Steps.md".
 * UI depends only on this interface; the backing implementation is bound in DI
 * ([org.armman.sakhi.di.LmpChangeModule]) — mirrors [org.armman.sakhi.data.reopen.ReopenRepository]
 * exactly, since this is the same small dedicated request/approval object pattern Reopen already
 * established (not the generic `POST /approvals` path an earlier investigation pass mistakenly
 * assumed this went through).
 */
interface LmpChangeRepository {
  /**
   * Submits a new LMP correction request for [beneficiaryId]. [sonographyImageAssetId] must
   * already be an uploaded media asset id — see [org.armman.sakhi.data.referral.ReferralApi]'s
   * upload-url/finalize flow, the only confirmed media-upload pipeline in this app; this function
   * does not perform the upload itself. Throws on failure — same throwing contract as
   * [org.armman.sakhi.data.reopen.ReopenRepository.submitReopenRequest], the caller is expected to
   * catch and show a message.
   */
  suspend fun submitLmpChangeRequest(
    beneficiaryId: String,
    newLmpDate: LocalDate,
    sonographyImageAssetId: String?,
    localRequestUuid: String,
  )

  /** True if [beneficiaryId] has at least one LMP change request whose `supervisorStatus` is
   * still `"PENDING"` — best-effort, mirrors [org.armman.sakhi.data.reopen.ReopenRepository
   * .hasPendingReopenRequest] exactly: a fetch failure reads as "no pending request known" rather
   * than failing the whole profile load. */
  suspend fun hasPendingLmpChangeRequest(beneficiaryId: String): Boolean

  /**
   * The most recently requested LMP change request for [beneficiaryId] whose `supervisorStatus`
   * is `"APPROVED"`, or null if none. Unlike Reopen's boolean-only [org.armman.sakhi.data.reopen
   * .ReopenRepository.hasApprovedReopenRequest], the caller needs the actual approved
   * [LmpChangeRequestRowDto.newLmpDate] value to regenerate the ANC schedule (see
   * [org.armman.sakhi.data.schedule.VisitScheduleCoordinator.onLmpOrEddApproved]), not just a
   * yes/no. Best-effort: a fetch failure returns null, the same safe default as every other read
   * here.
   */
  suspend fun approvedLmpChangeRequest(beneficiaryId: String): LmpChangeRequestRowDto?

  /** True if [beneficiaryId] has at least one LMP change request whose `supervisorStatus` is
   * `"REJECTED"` — mirrors [org.armman.sakhi.data.reopen.ReopenRepository
   * .hasRejectedReopenRequest] exactly (task-6's rejection-half pattern, reapplied here for
   * task 4). Best-effort, same as the others. */
  suspend fun hasRejectedLmpChangeRequest(beneficiaryId: String): Boolean

  /**
   * Task 2 (LMP/Reopen/Referral/Audit task list) — uploads [file] (the sonography report photo
   * captured on the ANC_VISIT form's `upload_sonography_report_image` field) as a real media
   * asset, so its returned id can be passed as [submitLmpChangeRequest]'s [sonographyImageAssetId].
   * Backend-confirmed 2026-09-02: `assetType: "LMP_SONOGRAPHY_REPORT"` is the value to send —
   * already a valid entry in `media-service`'s `MediaAssetType` enum, no backend change needed.
   * Same 3-step presigned-URL flow CR-Referral-02 established
   * ([org.armman.sakhi.data.referral.RemoteReferralRepository.uploadEvidence]:
   * `POST /media/upload-url` → raw S3 PUT → `POST /media` finalize) — reused via
   * [org.armman.sakhi.data.referral.ReferralApi] directly rather than duplicating a second Retrofit
   * interface for what are, underneath, generic `media-service` routes, not referral-owned ones.
   * Throws on failure, same convention as [submitLmpChangeRequest] — the caller decides how to
   * surface it (see [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator
   * .maybeCreateLmpChangeRequest], which treats a failure here as best-effort and never fails the
   * visit submission it's called from).
   */
  suspend fun uploadSonographyImage(file: File): Result<String>
}

/**
 * Task 2 — bundles what [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel] reads off the
 * ANC_VISIT form's own answers/captured-image state once she's filled the sonography Yes branch,
 * for [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator.submit] to act on after the
 * visit submission itself succeeds — mirrors [org.armman.sakhi.data.referral.ReferralCapture]'s
 * own role for the Referral tab exactly. [sonographyImageFilePath] is a real on-disk absolute
 * path (not a `content://` URI) — same "coordinator never resolves a URI itself" convention
 * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator.queueReferralFollowUpEvidence]'s
 * own doc establishes, keeping this coordinator Context-free and unit-testable.
 */
data class LmpChangeCapture(
  val newLmpDate: LocalDate,
  val sonographyImageFilePath: String,
)

internal fun LmpChangeRequestRowDto.isPending(): Boolean = supervisorStatus == SUPERVISOR_STATUS_PENDING
internal fun LmpChangeRequestRowDto.isApproved(): Boolean = supervisorStatus == SUPERVISOR_STATUS_APPROVED
internal fun LmpChangeRequestRowDto.isRejected(): Boolean = supervisorStatus == SUPERVISOR_STATUS_REJECTED
