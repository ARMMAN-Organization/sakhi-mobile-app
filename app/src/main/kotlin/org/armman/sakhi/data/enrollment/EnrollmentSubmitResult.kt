package org.armman.sakhi.data.enrollment

/**
 * Outcome of [EnrollmentRepository.submitEnrollment]. Distinguishes a
 * confirmed backend result (only obtainable while online, per the immediate
 * [EnrollmentSyncExecutor.runOne] attempt) from a purely local save that's
 * been queued for background sync (when offline) — the ViewModel uses this
 * to decide whether it's safe to navigate away yet, or must stay on Summary
 * and show the real validation/conflict error.
 */
sealed interface EnrollmentSubmitResult {
  /**
   * Offline: saved locally, queued for background sync via
   * [EnrollmentSyncScheduler]/[EnrollmentSyncWorker]. Nothing more can be
   * known right now — this is the offline-first guarantee: Submit never
   * blocks on connectivity when there isn't any.
   */
  data object QueuedOffline : EnrollmentSubmitResult

  /** Online: the backend confirmed the beneficiary was created. */
  data object Synced : EnrollmentSubmitResult

  /** Online: backend rejected as a possible duplicate (SRS FR-S-2.4/2.5). */
  data class DuplicateConflict(val message: String?) : EnrollmentSubmitResult

  /** Online: backend rejected for any other reason (validation, server error). */
  data class Failed(val message: String?) : EnrollmentSubmitResult
}
