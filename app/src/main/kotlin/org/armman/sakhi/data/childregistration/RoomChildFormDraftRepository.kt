package org.armman.sakhi.data.childregistration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first [ChildFormDraftRepository]: sync metadata in Room ([ChildFormDraftDao]), answers
 * payload in the encrypted [SecureKeyValueStore] — the exact hybrid split the mother twin
 * ([org.armman.sakhi.data.forms.RoomDynamicFormDraftRepository]) uses, for the same reason (reuse
 * the tested encrypted-storage path for anything PII, keep Room to non-sensitive sync bookkeeping).
 *
 * [submitDraft] additionally attempts the real backend calls immediately when online, so the Sakhi
 * sees an actual validation/conflict error before leaving the form. Offline, both methods behave
 * the same: save locally, let [ChildFormSyncWorker] sync in the background.
 */
@Singleton
class RoomChildFormDraftRepository @Inject constructor(
  private val dao: ChildFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val connectivityChecker: ConnectivityChecker,
  private val syncExecutor: ChildFormSyncExecutor,
) : ChildFormDraftRepository {

  override suspend fun saveDraft(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): Result<Unit> = runCatching {
    // Local save only. Uploading is the Sakhi's explicit Data Upload action (SRS §3A.1 manual
    // trigger); this deliberately schedules nothing.
    saveLocally(localBeneficiaryId, formCode, formVersionId, localSubmissionUuid, answers, registrationDate)
  }

  override suspend fun submitDraft(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ): ChildFormSubmitResult {
    saveLocally(localBeneficiaryId, formCode, formVersionId, localSubmissionUuid, answers, registrationDate)

    // Offline: the draft is safely persisted and waits in the queue for the Sakhi's Data Upload
    // tap. Nothing is scheduled here — a WorkManager job enqueued now would carry a
    // NetworkType.CONNECTED constraint and fire by itself on reconnect, which is the auto-sync
    // SRS §3A.1 rules out.
    if (!connectivityChecker.isOnline()) {
      return ChildFormSubmitResult.QueuedOffline
    }

    return when (val result = syncExecutor.runOne(localBeneficiaryId)) {
      is ChildFormSyncItemResult.Synced -> ChildFormSubmitResult.Synced
      is ChildFormSyncItemResult.DuplicateConflict -> ChildFormSubmitResult.DuplicateConflict
      is ChildFormSyncItemResult.Failed -> ChildFormSubmitResult.Failed(result.message)
      is ChildFormSyncItemResult.Retryable, null -> {
        // Transient (connectivity dropped mid-call despite the isOnline() check), or no draft row
        // found (shouldn't happen right after saveLocally — guard only). Fall back to the
        // offline-first guarantee rather than blocking the Sakhi indefinitely: the draft stays
        // PENDING for the next manual Data Upload.
        ChildFormSubmitResult.QueuedOffline
      }
    }
  }

  override suspend fun getUploadRecords(): List<FormUploadRecord> =
    dao.getAll().map { it.toUploadRecord() }

  override fun observeUploadRecords(): Flow<List<FormUploadRecord>> =
    dao.observeAll().map { entities -> entities.map { it.toUploadRecord() } }

  private fun ChildFormDraftEntity.toUploadRecord() = FormUploadRecord(
    localBeneficiaryId = localBeneficiaryId,
    formCode = formCode,
    syncStatus = syncStatus,
    createdAtEpochMillis = createdAtEpochMillis,
  )

  private suspend fun saveLocally(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ) {
    val payload = ChildFormDraftPayload(answers = answers, registrationDateIso = registrationDate.toString())
    secureStore.putString(childFormDraftPayloadKey(localBeneficiaryId), childFormDraftGson.toJson(payload))

    val existing = dao.getByLocalBeneficiaryId(localBeneficiaryId)
    dao.upsert(
      ChildFormDraftEntity(
        localBeneficiaryId = localBeneficiaryId,
        formCode = formCode,
        formVersionId = formVersionId,
        localSubmissionUuid = localSubmissionUuid,
        // Re-saving (edited before first sync) resets to PENDING so the sync worker picks up the
        // new payload rather than skipping a stale SYNCED/FAILED row — same rationale as the mother
        // repository.
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
