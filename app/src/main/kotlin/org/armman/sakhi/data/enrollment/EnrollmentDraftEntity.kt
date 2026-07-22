package org.armman.sakhi.data.enrollment

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room-persisted sync *metadata* for one enrollment draft. Deliberately holds no PII — the actual
 * [EnrollmentRecord] payload (name, phone, health history, etc.) stays in the existing
 * Keystore-encrypted [org.armman.sakhi.data.auth.session.SecureKeyValueStore], keyed by
 * [beneficiaryId]. This entity only tracks *where the draft is in the sync lifecycle*, which is
 * why Room (a plain SQLite file, unencrypted by default) is an acceptable home for it: sync
 * status, timestamps, and retry count are not sensitive.
 *
 * [beneficiaryId] is the app-generated UUID from [EnrollmentRecord.beneficiaryId] — the natural
 * key end to end, so metadata row and encrypted payload are always found together.
 */
@Entity(tableName = "enrollment_drafts")
data class EnrollmentDraftEntity(
  @PrimaryKey val beneficiaryId: String,
  val syncStatus: EnrollmentSyncStatus,
  val createdAtEpochMillis: Long,
  val lastAttemptAtEpochMillis: Long?,
  val retryCount: Int,
  /** Populated once [syncStatus] reaches [EnrollmentSyncStatus.SYNCED] — the server's `data.id`. */
  val remoteBeneficiaryId: String?,
  /** Last failure reason (mapper validation message, HTTP error body, etc.) — for a future
   * "Failed — why?" UI affordance; never used for control flow. */
  val lastErrorMessage: String?,
)
