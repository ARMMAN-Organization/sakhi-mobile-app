package org.armman.sakhi.data.delivery

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
 * All of a future delivery-child-registration background worker's real logic, kept in a plain
 * class for the same reason [DeliveryFormSyncExecutor] is — a `CoroutineWorker` needs a real
 * `Context`/`WorkerParameters` this repo's JVM-only test setup can't construct. No WorkManager
 * scheduler/worker is wired up for this queue yet, matching [DeliveryFormSyncExecutor]'s own
 * "out of scope for this pass" note; [run] exists so one can be added later without touching this
 * class.
 */
@Singleton
class DeliveryChildRegistrationSyncExecutor @Inject constructor(
  private val dao: DeliveryChildRegistrationDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val coordinator: DeliveryChildRegistrationSubmissionCoordinator,
) {

  /** Processes every PENDING/FAILED draft. */
  suspend fun run(): EnrollmentSyncOutcome {
    dao.reclaimStaleSyncing()

    val pending = dao.getPendingSync()
    if (pending.isEmpty()) return EnrollmentSyncOutcome.COMPLETED

    var anyRetryableFailure = false
    for (draft in pending) {
      if (attemptSync(draft) is DeliveryChildRegistrationSyncItemResult.Retryable) {
        anyRetryableFailure = true
      }
    }

    return if (anyRetryableFailure) {
      EnrollmentSyncOutcome.RETRYABLE_FAILURE
    } else {
      EnrollmentSyncOutcome.COMPLETED
    }
  }

  /** Attempts an immediate sync for one specific draft — right after Submit while online —
   * bypassing any queue so [RoomDeliveryChildRegistrationDraftRepository] can react to the real
   * backend result. Returns null if there's no draft row for this id. */
  suspend fun runOne(localSubmissionUuid: String): DeliveryChildRegistrationSyncItemResult? {
    val draft = dao.getByLocalSubmissionUuid(localSubmissionUuid) ?: return null
    if (draft.syncStatus == EnrollmentSyncStatus.SYNCED) {
      // Already synced by an earlier attempt (e.g. a resumed session re-entering this step) —
      // the session row already reflects it; nothing new to report without re-hitting the network
      // for no reason.
      return DeliveryChildRegistrationSyncItemResult.Synced
    }
    return attemptSync(draft)
  }

  private suspend fun attemptSync(draft: DeliveryChildRegistrationDraftEntity): DeliveryChildRegistrationSyncItemResult {
    dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

    val payload = loadPayload(draft.localSubmissionUuid)
    if (payload == null) {
      val message = "Local draft payload not found"
      markFailed(draft, message)
      return DeliveryChildRegistrationSyncItemResult.Failed(message)
    }

    return try {
      val result = coordinator.submit(
        localSessionUuid = draft.localSessionUuid,
        serverBeneficiaryId = draft.serverBeneficiaryId,
        localSubmissionUuid = draft.localSubmissionUuid,
        formVersionId = draft.formVersionId,
        answers = payload.answers,
      )

      result.fold(
        onSuccess = {
          dao.upsert(
            draft.copy(
              syncStatus = EnrollmentSyncStatus.SYNCED,
              lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
              lastErrorMessage = null,
            ),
          )
          DeliveryChildRegistrationSyncItemResult.Synced
        },
        onFailure = { error ->
          when {
            error is IOException || error.cause is IOException -> {
              dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING))
              DeliveryChildRegistrationSyncItemResult.Retryable(
                (error as? DeliveryChildRegistrationSubmissionException)?.userMessage ?: error.message,
              )
            }

            else -> {
              val message = (error as? DeliveryChildRegistrationSubmissionException)?.userMessage
                ?: SubmitErrorCopy.humanize(error.message)
                ?: SubmitErrorCopy.GENERIC
              markFailed(draft, message)
              DeliveryChildRegistrationSyncItemResult.Failed(message)
            }
          }
        },
      )
    } catch (e: HttpException) {
      markFailed(draft, e.message())
      DeliveryChildRegistrationSyncItemResult.Failed(SubmitErrorCopy.GENERIC)
    }
  }

  private suspend fun markFailed(draft: DeliveryChildRegistrationDraftEntity, errorMessage: String?) {
    dao.upsert(
      draft.copy(
        syncStatus = EnrollmentSyncStatus.FAILED,
        lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
        retryCount = draft.retryCount + 1,
        lastErrorMessage = errorMessage,
      ),
    )
  }

  private fun loadPayload(localSubmissionUuid: String): DeliveryChildRegistrationDraftPayload? {
    val json = secureStore.getString(deliveryChildRegistrationDraftPayloadKey(localSubmissionUuid)) ?: return null
    return runCatching {
      deliveryChildRegistrationDraftGson.fromJson(json, DeliveryChildRegistrationDraftPayload::class.java)
    }.getOrNull()
  }
}
