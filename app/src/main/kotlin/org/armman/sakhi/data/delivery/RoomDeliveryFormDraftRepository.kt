package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmitErrorCopy
import org.armman.sakhi.data.schedule.VisitScheduleSyncExecutor
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first [DeliveryFormDraftRepository]: sync metadata in Room ([DeliveryFormDraftDao]),
 * answers payload in the encrypted [SecureKeyValueStore] — same hybrid split as every other queue
 * in this app. Mirrors [org.armman.sakhi.data.adhocform.RoomAdHocFormDraftRepository]'s shape.
 */
@Singleton
class RoomDeliveryFormDraftRepository @Inject constructor(
  private val dao: DeliveryFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val connectivityChecker: ConnectivityChecker,
  private val syncExecutor: DeliveryFormSyncExecutor,
  private val formAuditRepository: FormAuditRepository,
  /**
   * Reported live: a Sakhi who submits DELIVERY_VISIT while online, then immediately opens the
   * freshly-generated PP1 visit and submits it in the same session, was hitting
   * [org.armman.sakhi.data.visitform.VisitFormSubmissionException.NotYetSynced] even though she
   * was online throughout. Root cause: [org.armman.sakhi.data.schedule.VisitScheduleCoordinator
   * .onDeliveryRecorded] (called by [DeliveryFormSubmissionCoordinator.submit] just below, once the
   * delivery submission itself succeeds) generates the PP1/NN schedule rows purely locally — "writes
   * to Room and nothing else" per that coordinator's own doc — with no server id until
   * [VisitScheduleSyncExecutor] uploads them, which otherwise only happens on the Sakhi's next
   * manual Data Upload tap (SRS §3A.1). [submitDraft] now runs that upload itself, right here,
   * the moment we already know we're online — same "attempt now if online, else leave it for Data
   * Upload" shape this class already applies to the delivery submission itself.
   */
  private val visitScheduleSyncExecutor: VisitScheduleSyncExecutor,
) : DeliveryFormDraftRepository {

  override suspend fun submitDraft(
    localSubmissionUuid: String,
    localSessionUuid: String,
    localBeneficiaryId: String,
    formVersionId: String,
    answers: FormAnswers,
    deliveryDate: LocalDate,
    deliveryFormFilledOn: LocalDate,
  ): DeliveryFormSubmitResult {
    saveLocally(
      localSubmissionUuid = localSubmissionUuid,
      localSessionUuid = localSessionUuid,
      localBeneficiaryId = localBeneficiaryId,
      formVersionId = formVersionId,
      answers = answers,
      deliveryDate = deliveryDate,
      deliveryFormFilledOn = deliveryFormFilledOn,
    )

    // Offline: saved and queued for the Sakhi's next Data Upload tap — same manual-trigger rule
    // every other queue in this app follows.
    if (!connectivityChecker.isOnline()) {
      return DeliveryFormSubmitResult.QueuedOffline
    }

    val result = syncExecutor.runOne(localSubmissionUuid)
    if (result is DeliveryFormSyncItemResult.Synced) {
      // The delivery submission just reached the server, which means the PP1/NN schedule rows it
      // generates were also just created — locally only, see visitScheduleSyncExecutor's doc above.
      // Push them now, while we already know the device is online, instead of leaving the Sakhi to
      // hit NotYetSynced if she opens PP1/NN right away. Best-effort: this uploads every currently
      // unsynced schedule row for every beneficiary, not just this one, but a failure here changes
      // nothing — the rows simply stay queued for the next Data Upload, exactly as before this call
      // existed.
      runCatching { visitScheduleSyncExecutor.run() }
    }
    return result.toSubmitResult()
  }

  override suspend fun getAnswers(localSubmissionUuid: String): FormAnswers? {
    val json = secureStore.getString(deliveryFormDraftPayloadKey(localSubmissionUuid)) ?: return null
    return runCatching { deliveryFormDraftGson.fromJson(json, DeliveryFormDraftPayload::class.java) }
      .getOrNull()
      ?.answers
  }

  private fun DeliveryFormSyncItemResult?.toSubmitResult(): DeliveryFormSubmitResult = when (this) {
    is DeliveryFormSyncItemResult.Synced -> DeliveryFormSubmitResult.Synced(childBeneficiaryIds)
    is DeliveryFormSyncItemResult.Failed -> DeliveryFormSubmitResult.Failed(message)
    is DeliveryFormSyncItemResult.Retryable -> DeliveryFormSubmitResult.Failed(message ?: SubmitErrorCopy.GENERIC)
    null -> {
      // Only reachable if saveLocally() above somehow didn't leave a row behind — defensive only.
      DeliveryFormSubmitResult.Failed(SubmitErrorCopy.GENERIC)
    }
  }

  private suspend fun saveLocally(
    localSubmissionUuid: String,
    localSessionUuid: String,
    localBeneficiaryId: String,
    formVersionId: String,
    answers: FormAnswers,
    deliveryDate: LocalDate,
    deliveryFormFilledOn: LocalDate,
  ) {
    formAuditRepository.recordSaved(localSubmissionUuid, FORM_CODE_DELIVERY_VISIT)
    val payload = DeliveryFormDraftPayload(
      answers = answers,
      deliveryDateIso = deliveryDate.toString(),
      deliveryFormFilledOnIso = deliveryFormFilledOn.toString(),
    )
    secureStore.putString(deliveryFormDraftPayloadKey(localSubmissionUuid), deliveryFormDraftGson.toJson(payload))

    val existing = dao.getByLocalSubmissionUuid(localSubmissionUuid)
    dao.upsert(
      DeliveryFormDraftEntity(
        localSubmissionUuid = localSubmissionUuid,
        localBeneficiaryId = localBeneficiaryId,
        localSessionUuid = localSessionUuid,
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
      ),
    )
  }

  private companion object {
    const val FORM_CODE_DELIVERY_VISIT = "DELIVERY_VISIT"
  }
}
