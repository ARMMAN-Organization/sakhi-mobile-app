package org.armman.sakhi.data.enrollment

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first [EnrollmentRepository]: sync *metadata* (status/timestamps/retryCount) lives in
 * Room ([EnrollmentDraftDao]), while the [EnrollmentRecord] itself — which contains PII (name,
 * phone, address, health history) — stays in the Keystore-encrypted [SecureKeyValueStore], one
 * entry per beneficiary. This split avoids building new at-rest encryption for a Room database
 * from scratch and reuses the already-tested encrypted-storage path from [org.armman.sakhi.data.auth]
 * for anything sensitive.
 *
 * A local save always succeeds (Room + encrypted store are both on-device and always available).
 * [submitEnrollment] additionally attempts the real backend call immediately when online, so the
 * Sakhi sees an actual validation/conflict error before leaving the Summary screen — [saveEnrollment]
 * alone never did this, which is what let the app navigate away on records the backend would go on
 * to reject. Offline, both methods behave the same way: save locally and let
 * [EnrollmentSyncWorker] (task #15) sync in the background — that's what keeps enrollment
 * genuinely offline-first, since Submit never blocks on connectivity when there isn't any.
 */
@Singleton
class RoomEnrollmentRepository @Inject constructor(
  private val dao: EnrollmentDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val syncScheduler: EnrollmentSyncScheduler,
  private val connectivityChecker: ConnectivityChecker,
  private val syncExecutor: EnrollmentSyncExecutor,
) : EnrollmentRepository {

  override suspend fun saveEnrollment(record: EnrollmentRecord): Result<Unit> = runCatching {
    saveLocally(record)
    // Nudge a sync attempt immediately — a no-op (deferred by WorkManager) if currently offline,
    // an immediate submit if online. Never blocks this save on the outcome.
    syncScheduler.syncNow()
  }

  override suspend fun submitEnrollment(record: EnrollmentRecord): EnrollmentSubmitResult {
    saveLocally(record)

    if (!connectivityChecker.isOnline()) {
      syncScheduler.syncNow()
      return EnrollmentSubmitResult.QueuedOffline
    }

    // Online: attempt the real sync right now instead of only nudging WorkManager, so the caller
    // can react to the actual backend outcome before the Sakhi navigates away.
    return when (val result = syncExecutor.runOne(record.beneficiaryId)) {
      is EnrollmentSyncItemResult.Synced -> EnrollmentSubmitResult.Synced
      is EnrollmentSyncItemResult.DuplicateConflict ->
        EnrollmentSubmitResult.DuplicateConflict(result.message)
      is EnrollmentSyncItemResult.Failed -> EnrollmentSubmitResult.Failed(result.message)
      is EnrollmentSyncItemResult.Retryable, null -> {
        // Transient (e.g. connectivity dropped mid-call despite the isOnline() check above), or
        // no draft row found (shouldn't happen right after saveLocally — guard only). Fall back
        // to the offline-first guarantee rather than blocking the Sakhi indefinitely.
        syncScheduler.syncNow()
        EnrollmentSubmitResult.QueuedOffline
      }
    }
  }

  override suspend fun getEnrollment(beneficiaryId: String): EnrollmentRecord? {
    val json = secureStore.getString(enrollmentDraftPayloadKey(beneficiaryId)) ?: return null
    return runCatching { enrollmentRecordGson.fromJson(json, EnrollmentRecord::class.java) }
      .getOrNull()
  }

  private suspend fun saveLocally(record: EnrollmentRecord) {
    val payloadKey = enrollmentDraftPayloadKey(record.beneficiaryId)
    secureStore.putString(payloadKey, enrollmentRecordGson.toJson(record))

    val existing = dao.getByBeneficiaryId(record.beneficiaryId)
    dao.upsert(
      EnrollmentDraftEntity(
        beneficiaryId = record.beneficiaryId,
        // Re-saving an existing draft (e.g. edited before first sync) resets it back to PENDING
        // so the sync worker picks up the new payload rather than skipping a stale SYNCED/FAILED
        // row.
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = existing?.createdAtEpochMillis ?: Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = existing?.lastAttemptAtEpochMillis,
        retryCount = existing?.retryCount ?: 0,
        remoteBeneficiaryId = existing?.remoteBeneficiaryId,
        lastErrorMessage = null,
      ),
    )
  }
}
