package org.armman.sakhi.data.enrollment

/**
 * Lifecycle of a locally-saved enrollment draft as it moves toward the server. Drives both the
 * [EnrollmentSyncWorker] (task #15 — which statuses it should pick up) and any future UI badge
 * ("Pending sync" / "Failed — will retry" on My Beneficiaries).
 */
enum class EnrollmentSyncStatus {
  /** Saved on-device, not yet attempted — the normal state right after form submission. */
  PENDING,

  /** A sync attempt is currently in flight (set just before the network call, to avoid two
   * concurrent workers double-submitting the same draft). */
  SYNCING,

  /** Synced successfully; [EnrollmentDraftEntity.remoteBeneficiaryId] is populated. Terminal. */
  SYNCED,

  /** Server returned 409 (possible duplicate, SRS FR-S-2.4/2.5) — held until the Sakhi confirms
   * or discards via the task #17 UX. Not auto-retried. */
  DUPLICATE_CONFLICT,

  /** A sync attempt failed for a reason expected to persist without user action (e.g. a
   * validation the mapper's pre-checks missed) — surfaced, not silently retried forever. */
  FAILED,
}
