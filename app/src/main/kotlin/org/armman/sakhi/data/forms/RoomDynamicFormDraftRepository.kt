package org.armman.sakhi.data.forms

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first [DynamicFormDraftRepository]: sync metadata in Room ([DynamicFormDraftDao]),
 * answers payload in the encrypted [SecureKeyValueStore] — the exact hybrid split
 * [org.armman.sakhi.data.enrollment.RoomEnrollmentRepository] uses, for the same reason (reuse
 * the already-tested encrypted-storage path for anything PII, keep Room to non-sensitive sync
 * bookkeeping only).
 *
 * [submitDraft] additionally attempts the real backend calls immediately when online, so the
 * Sakhi sees an actual validation/conflict error before leaving the form — [saveDraft] alone
 * never did this, which is what let the app navigate away on drafts the backend would go on to
 * reject. Offline, both methods behave the same: save locally, let [DynamicFormSyncWorker] sync
 * in the background.
 */
@Singleton
class RoomDynamicFormDraftRepository @Inject constructor(
  private val dao: DynamicFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val syncScheduler: DynamicFormSyncScheduler,
  private val connectivityChecker: ConnectivityChecker,
  private val syncExecutor: DynamicFormSyncExecutor,
) : DynamicFormDraftRepository {

  override suspend fun saveDraft(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): Result<Unit> = runCatching {
    saveLocally(localBeneficiaryId, formCode, formVersionId, localSubmissionUuid, answers, registrationDate)
    // No-op (deferred by WorkManager) if offline, immediate submit if online — never blocks this
    // save on the outcome, which is what makes the Sakhi's "Submit" instant regardless of
    // connectivity.
    syncScheduler.syncNow()
  }

  override suspend fun submitDraft(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): DynamicFormSubmitResult {
    saveLocally(localBeneficiaryId, formCode, formVersionId, localSubmissionUuid, answers, registrationDate)

    if (!connectivityChecker.isOnline()) {
      syncScheduler.syncNow()
      return DynamicFormSubmitResult.QueuedOffline
    }

    return when (val result = syncExecutor.runOne(localBeneficiaryId)) {
      is DynamicFormSyncItemResult.Synced -> DynamicFormSubmitResult.Synced
      is DynamicFormSyncItemResult.DuplicateConflict ->
        DynamicFormSubmitResult.DuplicateConflict(result.message)
      is DynamicFormSyncItemResult.Failed -> DynamicFormSubmitResult.Failed(result.message)
      is DynamicFormSyncItemResult.Retryable, null -> {
        // Transient (connectivity dropped mid-call despite the isOnline() check above), or no
        // draft row found (shouldn't happen right after saveLocally — guard only). Fall back to
        // the offline-first guarantee rather than blocking the Sakhi indefinitely.
        syncScheduler.syncNow()
        DynamicFormSubmitResult.QueuedOffline
      }
    }
  }

  private suspend fun saveLocally(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ) {
    val payload = DynamicFormDraftPayload(answers = answers, registrationDateIso = registrationDate.toString())
    secureStore.putString(dynamicFormDraftPayloadKey(localBeneficiaryId), dynamicFormDraftGson.toJson(payload))

    val existing = dao.getByLocalBeneficiaryId(localBeneficiaryId)
    dao.upsert(
      DynamicFormDraftEntity(
        localBeneficiaryId = localBeneficiaryId,
        formCode = formCode,
        formVersionId = formVersionId,
        localSubmissionUuid = localSubmissionUuid,
        // Re-saving (edited before first sync) resets to PENDING so the sync worker picks up the
        // new payload rather than skipping a stale SYNCED/FAILED row — same rationale as the
        // static enrollment repository.
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = existing?.createdAtEpochMillis ?: Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = existing?.lastAttemptAtEpochMillis,
        retryCount = existing?.retryCount ?: 0,
        remoteBeneficiaryId = existing?.remoteBeneficiaryId,
        remoteSubmissionId = existing?.remoteSubmissionId,
        lastErrorMessage = null,
      ),
    )
  }
}
