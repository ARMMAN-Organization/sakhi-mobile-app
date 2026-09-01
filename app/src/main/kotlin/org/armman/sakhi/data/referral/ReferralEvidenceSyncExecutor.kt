package org.armman.sakhi.data.referral

import android.util.Log
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SakhiSync"

/**
 * CR-Referral-02: offline queue drain for captured evidence media (task requirement "offline
 * media queue + sync"). Same split-from-Worker rationale as every other executor in this app (a
 * `CoroutineWorker` needs a real `Context`/`WorkerParameters` this repo's JVM-only test setup
 * can't construct) — see [org.armman.sakhi.data.visitform.VisitFormSyncExecutor]'s doc.
 *
 * Referral Follow-up evidence (`case_paper_photo`/`further_investigation_photo`, captured via the
 * ad-hoc form pipeline) is queued already eligible — [ReferralEvidenceMediaEntity.submissionId]
 * is known synchronously by [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator] at
 * insert time, unlike the retired bespoke screen's two-phase `followupId` stamp-after-capture
 * flow (kept working for any already-queued row from that flow — see
 * [ReferralEvidenceDao.getPendingSync]'s doc for the dual-key eligibility check).
 */
@Singleton
class ReferralEvidenceSyncExecutor @Inject constructor(
  private val dao: ReferralEvidenceDao,
  private val referralRepository: ReferralRepository,
) {

  suspend fun run(): EnrollmentSyncOutcome {
    dao.reclaimStaleSyncing()

    val pending = dao.getPendingSync()
    if (pending.isEmpty()) return EnrollmentSyncOutcome.COMPLETED

    var anyRetryableFailure = false
    for (media in pending) {
      if (!attemptSync(media)) {
        anyRetryableFailure = true
      }
    }

    return if (anyRetryableFailure) EnrollmentSyncOutcome.RETRYABLE_FAILURE else EnrollmentSyncOutcome.COMPLETED
  }

  /** Returns true on success or a permanent (non-retryable) failure, false if this should be
   * retried on the next run — same true/false-as-"handled" shape callers of this executor check
   * via [run]'s aggregate outcome rather than a per-item result type (no immediate-attempt caller
   * needs a richer type yet, unlike [org.armman.sakhi.data.adhocform.AdHocFormSyncExecutor.runOne]). */
  private suspend fun attemptSync(media: ReferralEvidenceMediaEntity): Boolean {
    dao.upsert(media.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

    val file = File(media.localFilePath)
    if (!file.exists()) {
      // Nothing to upload any more (e.g. app storage was cleared) — permanent, not retryable.
      markFailed(media, "Captured file no longer exists on device")
      return true
    }

    val evidenceType = runCatching { ReferralEvidenceType.valueOf(media.evidenceType) }.getOrNull()
    if (evidenceType == null) {
      markFailed(media, "Unknown evidence type: ${media.evidenceType}")
      return true
    }

    val followupId = media.followupId
    val submissionId = media.submissionId
    if (followupId == null && submissionId == null) {
      // Defensive only — getPendingSync already filters these out, so this shouldn't be
      // reachable. Not a permanent failure (one of the two ids may still arrive once its parent
      // succeeds), so just leave it PENDING rather than marking it FAILED.
      dao.upsert(media.copy(syncStatus = EnrollmentSyncStatus.PENDING))
      return false
    }

    val result = referralRepository.uploadEvidence(
      referralId = media.referralId,
      followupId = followupId,
      submissionId = submissionId,
      evidenceType = evidenceType,
      file = file,
    )

    return result.fold(
      onSuccess = { remoteMediaId ->
        dao.upsert(
          media.copy(
            syncStatus = EnrollmentSyncStatus.SYNCED,
            lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
            lastErrorMessage = null,
            remoteMediaId = remoteMediaId,
          ),
        )
        true
      },
      onFailure = { error ->
        Log.w(TAG, "Referral evidence upload failed for ${media.localMediaUuid}", error)
        // Best-effort, connectivity-shaped: stay PENDING and let the next Data Upload retry it,
        // same non-blocking treatment [org.armman.sakhi.data.visitform.VisitFormSubmissionCoordinator
        // .triggerRiskAssessment]'s own doc establishes for referral creation itself — a media
        // upload failure must never be treated as a hard, user-facing error the way a form
        // submission failure is, since the Sakhi has already submitted her follow-up text.
        dao.upsert(
          media.copy(
            syncStatus = EnrollmentSyncStatus.PENDING,
            lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
            retryCount = media.retryCount + 1,
            lastErrorMessage = error.message,
          ),
        )
        false
      },
    )
  }

  private suspend fun markFailed(media: ReferralEvidenceMediaEntity, message: String) {
    dao.upsert(
      media.copy(
        syncStatus = EnrollmentSyncStatus.FAILED,
        lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
        retryCount = media.retryCount + 1,
        lastErrorMessage = message,
      ),
    )
  }
}
