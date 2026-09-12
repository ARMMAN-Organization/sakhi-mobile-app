package org.armman.sakhi.data.adhocform

import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.forms.SubmitErrorCopy
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first [AdHocFormDraftRepository]: sync metadata in Room ([AdHocFormDraftDao]), answers
 * payload in the encrypted [SecureKeyValueStore] — same hybrid split as every other queue in this
 * app. Mirrors [org.armman.sakhi.data.visitform.RoomVisitFormDraftRepository]'s shape exactly,
 * minus that class's two-call `serverVisitId` resume dance (there is no visit-creation step here).
 */
@Singleton
class RoomAdHocFormDraftRepository @Inject constructor(
  private val dao: AdHocFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val connectivityChecker: ConnectivityChecker,
  private val syncExecutor: AdHocFormSyncExecutor,
  private val formAuditRepository: FormAuditRepository,
) : AdHocFormDraftRepository {

  override suspend fun submitDraft(
    localFormInstanceUuid: String,
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    answers: FormAnswers,
    referralId: String?,
    capturedImagePaths: Map<String, String>,
  ): AdHocFormSubmitResult {
    saveLocally(
      localFormInstanceUuid, localBeneficiaryId, formCode, formVersionId, answers, referralId, capturedImagePaths,
    )

    // Offline: saved and queued for the Sakhi's next Data Upload tap — same manual-trigger rule
    // every other queue in this app follows.
    if (!connectivityChecker.isOnline()) {
      return AdHocFormSubmitResult.QueuedOffline
    }

    return syncExecutor.runOne(localFormInstanceUuid).toSubmitResult()
  }

  override suspend fun countByFormCode(localBeneficiaryId: String, formCode: String): Int =
    dao.getAll().count { it.localBeneficiaryId == localBeneficiaryId && it.formCode == formCode }

  override fun observeUploadRecords(): Flow<List<FormUploadRecord>> =
    dao.observeAll().map { entities -> entities.map { it.toUploadRecord() } }

  private fun AdHocFormDraftEntity.toUploadRecord() = FormUploadRecord(
    localBeneficiaryId = localBeneficiaryId,
    formCode = formCode,
    syncStatus = syncStatus,
    createdAtEpochMillis = createdAtEpochMillis,
    // Ad-hoc forms have no "is this a new pregnancy?" duplicate-review step (that's Mother
    // Registration-only, DUPLICATE_CONFLICT never occurs on this queue) -- always null here,
    // same as VisitFormDraftEntity.toUploadRecord()'s own equivalent field.
    pendingNewPregnancyBeneficiaryId = null,
  )

  private fun AdHocFormSyncItemResult?.toSubmitResult(): AdHocFormSubmitResult = when (this) {
    is AdHocFormSyncItemResult.Synced -> AdHocFormSubmitResult.Synced
    is AdHocFormSyncItemResult.Failed -> AdHocFormSubmitResult.Failed(message)
    is AdHocFormSyncItemResult.Retryable -> AdHocFormSubmitResult.Failed(message ?: SubmitErrorCopy.GENERIC)
    null -> {
      // Only reachable if saveLocally() above somehow didn't leave a row behind — defensive only.
      AdHocFormSubmitResult.Failed(SubmitErrorCopy.GENERIC)
    }
  }

  private suspend fun saveLocally(
    localFormInstanceUuid: String,
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    answers: FormAnswers,
    referralId: String?,
    capturedImagePaths: Map<String, String>,
  ) {
    // Unconditional — runs before the online/offline branch, so both the online-success and
    // offline-queued paths get a SAVED event. Same rule CR-035 established for every other queue.
    formAuditRepository.recordSaved(localFormInstanceUuid, formCode)
    val payload = AdHocFormDraftPayload(answers = answers, capturedImagePaths = capturedImagePaths)
    secureStore.putString(adHocFormDraftPayloadKey(localFormInstanceUuid), adHocFormDraftGson.toJson(payload))

    val existing = dao.getByLocalFormInstanceUuid(localFormInstanceUuid)
    dao.upsert(
      AdHocFormDraftEntity(
        localFormInstanceUuid = localFormInstanceUuid,
        localBeneficiaryId = localBeneficiaryId,
        formCode = formCode,
        formVersionId = formVersionId,
        // Re-saving (a retry from the same screen) resets to PENDING so the sync worker picks up
        // the fresh payload rather than skipping a stale SYNCED/FAILED row — same rationale as
        // every other queue.
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = existing?.createdAtEpochMillis ?: Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = existing?.lastAttemptAtEpochMillis,
        retryCount = existing?.retryCount ?: 0,
        serverSubmissionId = existing?.serverSubmissionId,
        lastErrorMessage = null,
        referralId = referralId,
      ),
    )
  }
}
