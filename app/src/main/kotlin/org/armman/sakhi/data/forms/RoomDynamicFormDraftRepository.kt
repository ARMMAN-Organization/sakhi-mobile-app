package org.armman.sakhi.data.forms

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import org.armman.sakhi.data.enrollment.DuplicateAcknowledgement
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.schedule.MotherEnrolmentScheduleTrigger
import org.armman.sakhi.data.schedule.VisitScheduleSyncExecutor
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only form whose submission generates an ANC schedule (CR-022). Kept here rather than reused
 * from [DynamicFormSubmissionCoordinator]'s private constant so the coupling is visible at the one
 * place that branches on it.
 */
private const val MOTHER_REGISTRATION_FORM_CODE = "MOTHER_REGISTRATION"

/** Temporary diagnostic tag for the online-enrollment-to-visit-submit chain (CR-026 debugging). */
private const val TAG = "SakhiSync"

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
  private val connectivityChecker: ConnectivityChecker,
  private val syncExecutor: DynamicFormSyncExecutor,
  private val scheduleTrigger: MotherEnrolmentScheduleTrigger,
  private val visitScheduleSyncExecutor: VisitScheduleSyncExecutor,
  private val formAuditRepository: FormAuditRepository,
) : DynamicFormDraftRepository {

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
  ): DynamicFormSubmitResult {
    saveLocally(localBeneficiaryId, formCode, formVersionId, localSubmissionUuid, answers, registrationDate)

    // CR-022: generate the ANC schedule the moment the form is submitted, on-device and with no
    // network (SRS FR-S-2.2). Deliberately here rather than in saveDraft — a partial save is not an
    // enrolment — and deliberately BEFORE the sync attempt below, so a schedule always exists by
    // the time the beneficiary can reach the server. The trigger swallows its own failures: a
    // missing schedule can be fixed later, a lost registration cannot.
    //
    // Gated on the form code. This repository is the generic dynamic-form path, so without the
    // guard ANY form carrying an `lmp_date` answer would silently generate an ANC series — a
    // re-registration or a future visit form routed through here would each mint one.
    if (formCode == MOTHER_REGISTRATION_FORM_CODE) {
      scheduleTrigger.generateFor(localBeneficiaryId, answers, registrationDate)
    }

    // Offline: the draft is safely persisted and waits in the queue for the Sakhi's Data Upload
    // tap. Nothing is scheduled here — a WorkManager job enqueued now would carry a
    // NetworkType.CONNECTED constraint and fire by itself on reconnect, which is the auto-sync
    // SRS §3A.1 rules out.
    if (!connectivityChecker.isOnline()) {
      Log.w(TAG, "submitDraft($localBeneficiaryId): isOnline()=false, queuing offline")
      return DynamicFormSubmitResult.QueuedOffline
    }

    val result = syncExecutor.runOne(localBeneficiaryId).toSubmitResult()
    Log.d(TAG, "submitDraft($localBeneficiaryId): beneficiary sync result=$result")

    // We already have a live connection right now, so push the freshly generated ANC schedule up
    // immediately too — without this it would sit unsynced until the Sakhi's next manual Data
    // Upload even though nothing is stopping it from going now (this is the online case only; SRS
    // §3A.1's manual-trigger rule is unaffected offline). Best-effort: any failure here leaves the
    // schedule PENDING for the next Data Upload exactly as before, and must never turn a
    // successful beneficiary registration into a reported failure.
    if (formCode == MOTHER_REGISTRATION_FORM_CODE && result == DynamicFormSubmitResult.Synced) {
      val scheduleSyncOutcome = runCatching { visitScheduleSyncExecutor.run() }
      Log.d(TAG, "submitDraft($localBeneficiaryId): immediate schedule sync outcome=$scheduleSyncOutcome")
    }

    return result
  }

  /**
   * Persists the Sakhi's confirmation of an FR-S-2.5 new-pregnancy prompt onto the draft, returns the
   * row to PENDING, and retries while online.
   *
   * Order matters: the acknowledgement is written to the encrypted payload BEFORE the status flips,
   * so a background upload racing this call can never pick the draft up without it and get rejected
   * a second time.
   */
  override suspend fun confirmNewPregnancy(
    localBeneficiaryId: String,
    existingBeneficiaryId: String,
  ): DynamicFormSubmitResult {
    val draft = dao.getByLocalBeneficiaryId(localBeneficiaryId)
    val payload = readPayload(localBeneficiaryId)
    // Nothing to resubmit. Reporting success here would tell the Sakhi an enrolment went through
    // that no longer exists on the device, so this stays a failure (generic copy, from the UI).
    if (draft == null || payload == null) return DynamicFormSubmitResult.Failed(null)

    secureStore.putString(
      dynamicFormDraftPayloadKey(localBeneficiaryId),
      dynamicFormDraftGson.toJson(
        payload.copy(
          duplicateAcknowledgement = DuplicateAcknowledgement(existingBeneficiaryId),
          // Answered — so Home must stop offering the prompt regardless of how this retry goes.
          pendingNewPregnancyBeneficiaryId = null,
        ),
      ),
    )
    // DUPLICATE_CONFLICT is deliberately excluded from getPendingSync() and reclaimStaleSyncing(),
    // so without this reset a confirmed draft could never be uploaded again.
    dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING, lastErrorMessage = null))

    if (!connectivityChecker.isOnline()) return DynamicFormSubmitResult.QueuedOffline
    return syncExecutor.runOne(localBeneficiaryId).toSubmitResult()
  }

  override suspend fun dismissNewPregnancyPrompt(localBeneficiaryId: String) {
    val payload = readPayload(localBeneficiaryId) ?: return
    if (payload.pendingNewPregnancyBeneficiaryId == null) return
    secureStore.putString(
      dynamicFormDraftPayloadKey(localBeneficiaryId),
      dynamicFormDraftGson.toJson(payload.copy(pendingNewPregnancyBeneficiaryId = null)),
    )
  }

  /** Shared by [submitDraft] and [confirmNewPregnancy] so the two can't drift on how a sync outcome
   * is reported to the UI. */
  private fun DynamicFormSyncItemResult?.toSubmitResult(): DynamicFormSubmitResult = when (this) {
    is DynamicFormSyncItemResult.Synced -> DynamicFormSubmitResult.Synced
    is DynamicFormSyncItemResult.DuplicateConflict -> DynamicFormSubmitResult.DuplicateConflict(outcome)
    is DynamicFormSyncItemResult.Failed -> DynamicFormSubmitResult.Failed(message, fieldErrors)
    is DynamicFormSyncItemResult.Retryable, null -> {
      // Transient (connectivity dropped mid-call despite the isOnline() check above), or no
      // draft row found (shouldn't happen right after saveLocally — guard only). Fall back to
      // the offline-first guarantee rather than blocking the Sakhi indefinitely: the draft stays
      // PENDING for the next manual Data Upload.
      DynamicFormSubmitResult.QueuedOffline
    }
  }

  private fun readPayload(localBeneficiaryId: String): DynamicFormDraftPayload? {
    val json = secureStore.getString(dynamicFormDraftPayloadKey(localBeneficiaryId)) ?: return null
    return runCatching {
      dynamicFormDraftGson.fromJson(json, DynamicFormDraftPayload::class.java)
    }.getOrNull()
  }

  override suspend fun getUploadRecords(): List<FormUploadRecord> =
    dao.getAll().map { it.toUploadRecord() }

  override fun observeUploadRecords(): Flow<List<FormUploadRecord>> =
    dao.observeAll().map { entities -> entities.map { it.toUploadRecord() } }

  /**
   * Reads the stored payload only for rows the backend rejected as duplicates, to surface an
   * unanswered new-pregnancy prompt on Home. Every other row skips the decrypt entirely — this maps
   * the whole draft list on each emission, and there is normally at most a handful of conflicts.
   */
  private fun DynamicFormDraftEntity.toUploadRecord() = FormUploadRecord(
    localBeneficiaryId = localBeneficiaryId,
    formCode = formCode,
    syncStatus = syncStatus,
    createdAtEpochMillis = createdAtEpochMillis,
    pendingNewPregnancyBeneficiaryId = if (syncStatus == EnrollmentSyncStatus.DUPLICATE_CONFLICT) {
      readPayload(localBeneficiaryId)?.pendingNewPregnancyBeneficiaryId
    } else {
      null
    },
  )

  private suspend fun saveLocally(
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    localSubmissionUuid: String,
    answers: FormAnswers,
    registrationDate: LocalDate,
  ) {
    // CR-035: unconditional — shared by saveDraft() and submitDraft(), both of which must record a
    // SAVED event.
    formAuditRepository.recordSaved(localBeneficiaryId, formCode)
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
