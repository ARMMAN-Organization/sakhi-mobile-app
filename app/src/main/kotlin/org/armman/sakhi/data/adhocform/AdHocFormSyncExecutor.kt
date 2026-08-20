package org.armman.sakhi.data.adhocform

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.SubmitErrorCopy
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All of a future ad-hoc-form background worker's real logic, kept in a plain class for the same
 * reason [org.armman.sakhi.data.visitform.VisitFormSyncExecutor] is — a `CoroutineWorker` needs a
 * real `Context`/`WorkerParameters` this repo's JVM-only test setup can't construct. No
 * WorkManager scheduler/worker is wired up for this queue yet (out of scope for this pass — see
 * the class using this, [RoomAdHocFormDraftRepository], for the immediate-online-attempt path that
 * IS wired today); [run] exists so one can be added later without touching this class.
 */
@Singleton
class AdHocFormSyncExecutor @Inject constructor(
  private val dao: AdHocFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val coordinator: AdHocFormSubmissionCoordinator,
) {

  /** Processes every PENDING/FAILED draft. */
  suspend fun run(): EnrollmentSyncOutcome {
    // Same rationale as every other queue's run(): a row stuck in SYNCING from a run that never
    // finished would otherwise be invisible to getPendingSync() forever.
    dao.reclaimStaleSyncing()

    val pending = dao.getPendingSync()
    if (pending.isEmpty()) return EnrollmentSyncOutcome.COMPLETED

    var anyRetryableFailure = false
    for (draft in pending) {
      if (attemptSync(draft) is AdHocFormSyncItemResult.Retryable) {
        anyRetryableFailure = true
      }
    }

    return if (anyRetryableFailure) {
      EnrollmentSyncOutcome.RETRYABLE_FAILURE
    } else {
      EnrollmentSyncOutcome.COMPLETED
    }
  }

  /**
   * Attempts an immediate sync for one specific draft — right after Submit while online —
   * bypassing any queue so [RoomAdHocFormDraftRepository] can react to the real backend result.
   * Returns null if there's no draft row for this id.
   */
  suspend fun runOne(localFormInstanceUuid: String): AdHocFormSyncItemResult? {
    val draft = dao.getByLocalFormInstanceUuid(localFormInstanceUuid) ?: return null
    if (draft.syncStatus == EnrollmentSyncStatus.SYNCED) return AdHocFormSyncItemResult.Synced
    return attemptSync(draft)
  }

  private suspend fun attemptSync(draft: AdHocFormDraftEntity): AdHocFormSyncItemResult {
    dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

    val payload = loadPayload(draft.localFormInstanceUuid)
    if (payload == null) {
      val message = "Local draft payload not found"
      markFailed(draft, message)
      return AdHocFormSyncItemResult.Failed(message)
    }

    return try {
      val result = coordinator.submit(
        localFormInstanceUuid = draft.localFormInstanceUuid,
        localBeneficiaryId = draft.localBeneficiaryId,
        formCode = draft.formCode,
        formVersionId = draft.formVersionId,
        answers = payload.answers,
      )

      result.fold(
        onSuccess = { submissionId ->
          dao.upsert(
            draft.copy(
              syncStatus = EnrollmentSyncStatus.SYNCED,
              lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
              lastErrorMessage = null,
              serverSubmissionId = submissionId,
            ),
          )
          AdHocFormSyncItemResult.Synced
        },
        onFailure = { error ->
          when {
            error is AdHocFormSubmissionException.NotYetSynced -> {
              // Not the Sakhi's fault and not permanent — the beneficiary just hasn't synced yet.
              // Stay PENDING (not FAILED) so this keeps quietly retrying on every future Data
              // Upload, same treatment VisitFormSyncExecutor gives its own NotYetSynced.
              dao.upsert(
                draft.copy(
                  syncStatus = EnrollmentSyncStatus.PENDING,
                  lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
                  lastErrorMessage = error.userMessage,
                ),
              )
              AdHocFormSyncItemResult.Retryable(error.userMessage)
            }

            error is IOException || error.cause is IOException -> {
              // Connectivity dropped mid-run — transient, worth retrying, not a recorded failure.
              dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING))
              AdHocFormSyncItemResult.Retryable(
                (error as? AdHocFormSubmissionException)?.userMessage ?: error.message,
              )
            }

            else -> {
              val message = (error as? AdHocFormSubmissionException)?.userMessage
                ?: SubmitErrorCopy.humanize(error.message)
                ?: SubmitErrorCopy.GENERIC
              markFailed(draft, message)
              AdHocFormSyncItemResult.Failed(message)
            }
          }
        },
      )
    } catch (e: HttpException) {
      // Retrofit's own message is the bare HTTP status line — accurate, but not actionable. Kept
      // on the draft for debugging, generic copy shown.
      markFailed(draft, e.message())
      AdHocFormSyncItemResult.Failed(SubmitErrorCopy.GENERIC)
    }
  }

  private suspend fun markFailed(draft: AdHocFormDraftEntity, errorMessage: String?) {
    dao.upsert(
      draft.copy(
        syncStatus = EnrollmentSyncStatus.FAILED,
        lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
        retryCount = draft.retryCount + 1,
        lastErrorMessage = errorMessage,
      ),
    )
  }

  private fun loadPayload(localFormInstanceUuid: String): AdHocFormDraftPayload? {
    val json = secureStore.getString(adHocFormDraftPayloadKey(localFormInstanceUuid)) ?: return null
    return runCatching { adHocFormDraftGson.fromJson(json, AdHocFormDraftPayload::class.java) }.getOrNull()
  }
}
