package org.armman.sakhi.data.enrollment

/**
 * Persistence boundary for submitted enrollments. The interface is shaped for
 * the future offline-first store + sync API; CR-015d ships an in-memory
 * implementation ([StaticEnrollmentRepository]).
 */
interface EnrollmentRepository {

  /**
   * Saves (or overwrites — re-submit safety) the record keyed by
   * [EnrollmentRecord.beneficiaryId].
   */
  suspend fun saveEnrollment(record: EnrollmentRecord): Result<Unit>

  /** The stored record for [beneficiaryId], or null if never saved. */
  suspend fun getEnrollment(beneficiaryId: String): EnrollmentRecord?
}
