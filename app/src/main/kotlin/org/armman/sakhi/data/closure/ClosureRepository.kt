package org.armman.sakhi.data.closure

/**
 * Everything that can stop [ClosureRepository.submitClosure] from completing — mirrors
 * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator]'s own
 * `AdHocFormSubmissionException` shape so callers (currently only that coordinator) can fold both
 * into the same retry/error-copy handling.
 */
sealed class ClosureSubmissionException(message: String) : Exception(message) {
  data class Failed(
    val httpCode: Int,
    val apiMessage: String?,
    val violations: List<String> = emptyList(),
  ) : ClosureSubmissionException("POST /closures failed: HTTP $httpCode — $apiMessage")

  data object NoIdReturned : ClosureSubmissionException("Closure submitted but no id was returned")
}

/**
 * Boundary for `POST /api/v1/closures` — separate from
 * [org.armman.sakhi.data.adhocform.AdHocFormDraftRepository] because a closure is a distinct
 * beneficiary-status-changing call the backend team stood up alongside (not instead of) the
 * generic `POST /forms/{formCode}/submissions` call the ANC/Child Closure ad-hoc forms already
 * make — see [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator] for where both are
 * invoked together.
 */
interface ClosureRepository {
  /** Returns the server-assigned closure id on success; throws [ClosureSubmissionException] (or a
   * network [java.io.IOException]) on failure — the same throwing contract
   * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator] already expects to catch. */
  suspend fun submitClosure(
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
  ): String
}
