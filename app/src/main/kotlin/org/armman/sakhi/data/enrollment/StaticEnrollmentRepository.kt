package org.armman.sakhi.data.enrollment

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory enrollment store (CR-015d). Singleton so records survive
 * ViewModel recreation within the process; real persistence (Room + sync)
 * replaces this behind the same interface.
 */
@Singleton
class StaticEnrollmentRepository @Inject constructor() : EnrollmentRepository {

  private val records = ConcurrentHashMap<String, EnrollmentRecord>()

  override suspend fun saveEnrollment(record: EnrollmentRecord): Result<Unit> {
    records[record.beneficiaryId] = record
    return Result.success(Unit)
  }

  override suspend fun getEnrollment(beneficiaryId: String): EnrollmentRecord? =
    records[beneficiaryId]
}
