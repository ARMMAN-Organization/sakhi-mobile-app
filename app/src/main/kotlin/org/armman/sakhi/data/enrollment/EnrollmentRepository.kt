package org.armman.sakhi.data.enrollment

/**
 * Persistence boundary for submitted enrollments. The interface is shaped for
 * the future offline-first store + sync API; CR-015d ships an in-memory
 * implementation ([StaticEnrollmentRepository]).
 */
interface EnrollmentRepository {

  /**
   * Saves (or overwrites — re-submit safety) the record keyed by
   * [EnrollmentRecord.beneficiaryId]. Always succeeds locally; does not wait
   * for or report the backend outcome — prefer [submitEnrollment] from the
   * Submit button so a validation/conflict error while online is caught
   * before the user navigates away.
   */
  suspend fun saveEnrollment(record: EnrollmentRecord): Result<Unit>

  /**
   * Saves the record locally, then — only while online — attempts the real
   * backend submission immediately and returns its outcome, so the caller
   * can keep the user on-screen and show the actual error instead of
   * navigating away on a local save that says nothing about the backend.
   * While offline, saves locally and queues background sync as before
   * ([EnrollmentSubmitResult.QueuedOffline]) — this never blocks on
   * connectivity, preserving the offline-first guarantee.
   */
  suspend fun submitEnrollment(record: EnrollmentRecord): EnrollmentSubmitResult

  /** The stored record for [beneficiaryId], or null if never saved. */
  suspend fun getEnrollment(beneficiaryId: String): EnrollmentRecord?
}
