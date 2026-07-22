package org.armman.sakhi.data.enrollment

private const val PAYLOAD_KEY_PREFIX = "enrollment_draft_payload_"

/**
 * The [org.armman.sakhi.data.auth.session.SecureKeyValueStore] key under which one beneficiary's
 * encrypted [EnrollmentRecord] payload is stored. Shared by [RoomEnrollmentRepository] (writes) and
 * [EnrollmentSyncWorker] (reads) so the two can never drift apart.
 */
internal fun enrollmentDraftPayloadKey(beneficiaryId: String): String =
  "$PAYLOAD_KEY_PREFIX$beneficiaryId"
