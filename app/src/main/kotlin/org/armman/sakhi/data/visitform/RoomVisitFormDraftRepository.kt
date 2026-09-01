package org.armman.sakhi.data.visitform

import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.referral.ReferralCapture
import org.armman.sakhi.data.rules.RiskGradingResult
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first [VisitFormDraftRepository]: sync metadata in Room ([VisitFormDraftDao]), answers
 * payload in the encrypted [SecureKeyValueStore] — the same hybrid split every other queue in this
 * app uses, for the same reason (reuse the already-tested encrypted-storage path for anything PII,
 * keep Room to non-sensitive sync bookkeeping only).
 *
 * [submitDraft] preserves the exact pre-CR-026b behavior for the immediate online case — it still
 * calls straight through to [VisitFormSyncExecutor.runOne] (which calls
 * [VisitFormSubmissionCoordinator.submit] exactly as [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.onFinish]
 * used to do directly) and reports the real outcome. What's new is that the submission is saved
 * locally *first*, so a failed immediate attempt no longer vanishes — it stays queued for the next
 * manual Data Upload instead of being lost.
 */
@Singleton
class RoomVisitFormDraftRepository @Inject constructor(
  private val dao: VisitFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val connectivityChecker: ConnectivityChecker,
  private val syncExecutor: VisitFormSyncExecutor,
  private val formAuditRepository: FormAuditRepository,
) : VisitFormDraftRepository {

  override suspend fun submitDraft(
    localScheduleUuid: String,
    formCode: String,
    formVersionId: String,
    answers: FormAnswers,
    visitDate: LocalDate,
    riskResult: RiskGradingResult?,
    referralCapture: ReferralCapture?,
  ): VisitFormSubmitResult {
    saveLocally(localScheduleUuid, formCode, formVersionId, answers, visitDate, riskResult, referralCapture)

    // Offline: saved and queued for the Sakhi's next Data Upload tap. Nothing scheduled here —
    // same SRS §3A.1 manual-trigger rule every other queue in this app follows.
    if (!connectivityChecker.isOnline()) {
      return VisitFormSubmitResult.QueuedOffline
    }

    return syncExecutor.runOne(localScheduleUuid).toSubmitResult()
  }

  override suspend fun getUploadRecords(): List<FormUploadRecord> =
    dao.getAll().map { it.toUploadRecord() }

  override fun observeUploadRecords(): Flow<List<FormUploadRecord>> =
    dao.observeAll().map { entities -> entities.map { it.toUploadRecord() } }

  private fun VisitFormDraftEntity.toUploadRecord() = FormUploadRecord(
    localBeneficiaryId = localScheduleUuid,
    formCode = formCode,
    syncStatus = syncStatus,
    createdAtEpochMillis = createdAtEpochMillis,
  )

  private fun VisitFormSyncItemResult?.toSubmitResult(): VisitFormSubmitResult = when (this) {
    is VisitFormSyncItemResult.Synced -> VisitFormSubmitResult.Synced(outcome)
    is VisitFormSyncItemResult.Failed -> VisitFormSubmitResult.Failed(message)
    is VisitFormSyncItemResult.Retryable -> {
      // Same immediate-feedback contract as before CR-026b: the Sakhi sees this as a failure
      // right now (e.g. NotYetSynced's "hasn't finished syncing yet" copy, or a dropped
      // connection). What's different is the draft saved above stays queued regardless, so the
      // next Data Upload retries it automatically — no resubmission needed on her part.
      VisitFormSubmitResult.Failed(message ?: SubmitErrorCopy.GENERIC)
    }
    null -> {
      // Only reachable if saveLocally() above somehow didn't leave a row behind — defensive only.
      VisitFormSubmitResult.Failed(SubmitErrorCopy.GENERIC)
    }
  }

  private suspend fun saveLocally(
    localScheduleUuid: String,
    formCode: String,
    formVersionId: String,
    answers: FormAnswers,
    visitDate: LocalDate,
    riskResult: RiskGradingResult?,
    referralCapture: ReferralCapture?,
  ) {
    // CR-035: unconditional — runs before the online/offline branch in submitDraft, so both the
    // online-success and offline-queued paths get a SAVED event.
    formAuditRepository.recordSaved(localScheduleUuid, formCode)
    val payload = VisitFormDraftPayload(answers = answers, riskResult = riskResult, referralCapture = referralCapture)
    secureStore.putString(visitFormDraftPayloadKey(localScheduleUuid), visitFormDraftGson.toJson(payload))

    val existing = dao.getByLocalScheduleUuid(localScheduleUuid)
    dao.upsert(
      VisitFormDraftEntity(
        localScheduleUuid = localScheduleUuid,
        formCode = formCode,
        formVersionId = formVersionId,
        // Minted once, on this draft's very first save, and preserved on every re-save after
        // that — same once-per-draft contract as `formVersionId`/`createdAtEpochMillis` below, so
        // a resumed sync after a step-2-only failure replays the same idempotency key on
        // `POST /forms/:formCode/submissions` instead of a fresh one each attempt.
        localSubmissionUuid = existing?.localSubmissionUuid ?: newLocalVisitSubmissionUuid(),
        visitDateIso = visitDate.toString(),
        // Re-saving (a retry from the same screen, or a re-submit before the first attempt's
        // background sync ran) resets to PENDING so the sync worker picks up the fresh payload
        // rather than skipping a stale SYNCED/FAILED row — same rationale as the other queues.
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = existing?.createdAtEpochMillis ?: Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = existing?.lastAttemptAtEpochMillis,
        retryCount = existing?.retryCount ?: 0,
        // Preserved across a re-save: if a prior attempt already got the visit created
        // server-side, that must not be forgotten just because the Sakhi is retrying the form
        // submission step.
        serverVisitId = existing?.serverVisitId,
        lastErrorMessage = null,
      ),
    )
  }
}
