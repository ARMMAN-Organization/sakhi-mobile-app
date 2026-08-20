package org.armman.sakhi.data.closure

import org.armman.sakhi.data.enrollment.ApiErrorParser
import javax.inject.Inject
import javax.inject.Singleton

/** Real [ClosureRepository] backed by `POST /api/v1/closures`. No offline cache/fallback of its
 * own (unlike the read-side repositories in this app) — a closure is a write, not something to
 * serve from a stale cache; offline resilience for it comes entirely from
 * [org.armman.sakhi.data.adhocform.AdHocFormSyncExecutor]'s existing retry queue, which this
 * throws into on failure exactly like [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]'s
 * own form-submission call does. */
@Singleton
class RemoteClosureRepository @Inject constructor(
  private val closureApi: ClosureApi,
) : ClosureRepository {

  override suspend fun submitClosure(
    localClosureUuid: String,
    beneficiaryId: String,
    closureType: String,
    closureReasonLookupValueId: String,
    eventDate: String?,
    closureDate: String,
    submittedByUserId: String,
    supervisorStatus: String?,
    supervisorId: String?,
    supervisorNotes: String?,
  ): String {
    val request = ClosureRequestDto(
      localClosureUuid = localClosureUuid,
      beneficiaryId = beneficiaryId,
      closureType = closureType,
      closureReasonLookupValueId = closureReasonLookupValueId,
      eventDate = eventDate,
      closureDate = closureDate,
      submittedByUserId = submittedByUserId,
      supervisorStatus = supervisorStatus,
      supervisorId = supervisorId,
      supervisorNotes = supervisorNotes,
    )
    val response = closureApi.createClosure(request)
    if (!response.isSuccessful) {
      val rawBody = response.errorBody()?.string()
      val apiError = ApiErrorParser.parse(rawBody)
      throw ClosureSubmissionException.Failed(
        httpCode = response.code(),
        apiMessage = apiError.message?.takeIf { it != rawBody },
        violations = apiError.violations,
      )
    }
    return response.body()?.data?.id ?: throw ClosureSubmissionException.NoIdReturned
  }
}
